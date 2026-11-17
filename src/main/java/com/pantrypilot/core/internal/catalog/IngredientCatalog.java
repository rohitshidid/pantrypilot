package com.pantrypilot.core.internal.catalog;

import com.pantrypilot.core.internal.CoreException;
import com.pantrypilot.shared.CatalogMatch;
import com.pantrypilot.shared.CatalogService;
import com.pantrypilot.shared.QuantityService;
import com.pantrypilot.shared.Unit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The canonical ingredient catalog and the unit model. This is what the AI
 * module calls to turn "roma tomatoes" into ingredient 1, and what everything
 * else calls to convert between count, grams and millilitres.
 *
 * <p>The catalog is read far more often than it changes (once per line of every
 * receipt and recipe), so it is kept in memory as an immutable snapshot. A
 * change swaps in a whole new snapshot; readers never see a half-built one and
 * never take a lock.
 */
@Service
public class IngredientCatalog implements CatalogService, QuantityService {

    private static final int SCALE = 3;

    public record IngredientView(long id, String name, BigDecimal gramsEach, BigDecimal gramsPerMl,
            StorageLocation defaultLocation, List<String> aliases) {
    }

    /** Everything a lookup needs, built once and then only read. */
    private record Snapshot(Map<String, Ingredient> byName, Map<Long, Ingredient> byId,
            List<String> names, Map<Long, List<String>> aliases) {
    }

    private final IngredientRepository ingredients;
    private final IngredientAliasRepository aliases;
    private final TransactionTemplate transaction;
    /** volatile: a thread that reads this after another thread replaced it must see the new one. */
    private volatile Snapshot snapshot;

    public IngredientCatalog(IngredientRepository ingredients, IngredientAliasRepository aliases,
            TransactionTemplate transaction) {
        this.ingredients = ingredients;
        this.aliases = aliases;
        this.transaction = transaction;
    }

    // ---- matching names -----------------------------------------------------

    /**
     * Tries, in order: the exact name or alias, its singular, the longest
     * catalog name inside the text ("large roma tomato"), then one or two typos.
     * Each step down is reported with lower confidence.
     */
    @Override
    public Optional<CatalogMatch> resolve(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Snapshot s = snapshot();
        String key = normalize(name);
        Ingredient exact = s.byName().get(key);
        if (exact == null) {
            exact = s.byName().get(singular(key));
        }
        if (exact != null) {
            return Optional.of(match(exact, 1.0));
        }
        String best = null;
        String padded = " " + singularWords(key) + " ";
        for (String candidate : s.names()) {
            if (padded.contains(" " + candidate + " ") && (best == null || candidate.length() > best.length())) {
                best = candidate;
            }
        }
        if (best != null) {
            return Optional.of(match(s.byName().get(best), 0.7));
        }
        for (String candidate : s.names()) {
            if (candidate.length() >= 5 && editDistance(key, candidate) <= 2) {
                return Optional.of(match(s.byName().get(candidate), 0.6));
            }
        }
        return Optional.empty();
    }

    /** Shared-catalog entries whose name or alias contains the text; everything when the text is blank. */
    public List<IngredientView> search(String text) {
        Snapshot s = snapshot();
        String key = text == null ? "" : normalize(text);
        List<IngredientView> found = new ArrayList<>();
        for (Ingredient ingredient : s.byId().values()) {
            List<String> also = s.aliases().getOrDefault(ingredient.getId(), List.of());
            if (key.isEmpty() || ingredient.getName().contains(key) || also.stream().anyMatch(a -> a.contains(key))) {
                found.add(view(ingredient, also));
            }
        }
        found.sort((a, b) -> a.name().compareTo(b.name()));
        return found;
    }

    /** Adds an ingredient to the shared catalog, for food the seed does not know. */
    public IngredientView add(String name, List<String> alsoKnownAs, BigDecimal gramsEach, BigDecimal gramsPerMl,
            StorageLocation defaultLocation, Integer shelfDaysPantry, Integer shelfDaysFridge, Integer shelfDaysFreezer) {
        if (name == null || normalize(name).isEmpty()) {
            throw CoreException.invalid("An ingredient name is required");
        }
        String key = normalize(name);
        List<String> extra = alsoKnownAs == null ? List.of()
                : alsoKnownAs.stream().map(IngredientCatalog::normalize).filter(a -> !a.isEmpty() && !a.equals(key))
                        .distinct().toList();
        requirePositive(gramsEach, "The weight of one item");
        requirePositive(gramsPerMl, "The density");
        Snapshot s = snapshot();
        for (String candidate : concat(key, extra)) {
            if (candidate.length() > 100) {
                throw CoreException.invalid("Names can be at most 100 characters");
            }
            if (s.byName().containsKey(candidate)) {
                throw CoreException.conflict("\"" + candidate + "\" is already in the catalog as "
                        + s.byName().get(candidate).getName());
            }
        }
        // Ingredient and aliases are saved together or not at all.
        Ingredient saved = transaction.execute(status -> {
            Ingredient ingredient = ingredients.save(new Ingredient(Ingredient.SHARED, key, IngredientKind.INGREDIENT,
                    gramsEach, gramsPerMl, defaultLocation == null ? StorageLocation.PANTRY : defaultLocation,
                    shelfDaysPantry, shelfDaysFridge, shelfDaysFreezer));
            extra.forEach(alias -> aliases.save(new IngredientAlias(ingredient.getId(), alias)));
            return ingredient;
        });
        // Only now, after the commit, may other threads build a snapshot that includes it.
        snapshot = null;
        return view(saved, extra);
    }

    // ---- converting units ---------------------------------------------------

    /**
     * Everything goes through grams: count x grams-each, or millilitres x density.
     * Empty when the ingredient lacks the number that conversion needs.
     */
    @Override
    public Optional<BigDecimal> convert(BigDecimal quantity, Unit from, Unit to, long ingredientId) {
        if (quantity == null || from == null || to == null) {
            return Optional.empty();
        }
        if (from == to) {
            return Optional.of(quantity);
        }
        Ingredient ingredient = snapshot().byId().get(ingredientId);
        if (ingredient == null) {
            return Optional.empty();
        }
        BigDecimal grams = switch (from) {
            case G -> quantity;
            case COUNT -> ingredient.getGramsEach() == null ? null : quantity.multiply(ingredient.getGramsEach());
            case ML -> ingredient.getGramsPerMl() == null ? null : quantity.multiply(ingredient.getGramsPerMl());
        };
        if (grams == null) {
            return Optional.empty();
        }
        BigDecimal result = switch (to) {
            case G -> grams;
            case COUNT -> ingredient.getGramsEach() == null ? null
                    : grams.divide(ingredient.getGramsEach(), SCALE, RoundingMode.HALF_UP);
            case ML -> ingredient.getGramsPerMl() == null ? null
                    : grams.divide(ingredient.getGramsPerMl(), SCALE, RoundingMode.HALF_UP);
        };
        return Optional.ofNullable(result);
    }

    // ---- snapshot -----------------------------------------------------------

    private Snapshot snapshot() {
        Snapshot current = snapshot;
        if (current == null) {
            // Two threads may both build one; they build the same thing, so either may win.
            current = load();
            snapshot = current;
        }
        return current;
    }

    private Snapshot load() {
        Map<String, Ingredient> byName = new HashMap<>();
        Map<Long, Ingredient> byId = new HashMap<>();
        List<String> names = new ArrayList<>();
        Map<Long, List<String>> aliasesById = new HashMap<>();
        for (Ingredient ingredient : ingredients.findByHouseholdIdOrderById(Ingredient.SHARED)) {
            byId.put(ingredient.getId(), ingredient);
            byName.put(ingredient.getName(), ingredient);
            names.add(ingredient.getName());
        }
        for (IngredientAlias alias : aliases.findAll()) {
            Ingredient ingredient = byId.get(alias.getIngredientId());
            if (ingredient != null) {
                byName.put(alias.getAlias(), ingredient);
                names.add(alias.getAlias());
                aliasesById.computeIfAbsent(ingredient.getId(), id -> new ArrayList<>()).add(alias.getAlias());
            }
        }
        return new Snapshot(Map.copyOf(byName), Map.copyOf(byId), List.copyOf(names), Map.copyOf(aliasesById));
    }

    // ---- helpers ------------------------------------------------------------

    private static CatalogMatch match(Ingredient ingredient, double confidence) {
        return new CatalogMatch(ingredient.getId(), ingredient.getName(), confidence);
    }

    private static IngredientView view(Ingredient i, List<String> aliases) {
        return new IngredientView(i.getId(), i.getName(), i.getGramsEach(), i.getGramsPerMl(),
                i.getDefaultLocation(), List.copyOf(aliases));
    }

    private static List<String> concat(String first, List<String> rest) {
        List<String> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private static void requirePositive(BigDecimal value, String what) {
        if (value != null && value.signum() <= 0) {
            throw CoreException.invalid(what + " must be more than zero");
        }
    }

    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9% -]", " ").replaceAll("\\s+", " ").strip();
    }

    private static String singularWords(String s) {
        StringBuilder sb = new StringBuilder();
        for (String word : s.split(" ")) {
            sb.append(singular(word)).append(' ');
        }
        return sb.toString().strip();
    }

    private static String singular(String s) {
        if (s.endsWith("oes")) {
            return s.substring(0, s.length() - 2);
        }
        if (s.endsWith("ies") && s.length() > 4) {
            return s.substring(0, s.length() - 3) + "y";
        }
        if (s.endsWith("s") && !s.endsWith("ss") && s.length() > 3) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Levenshtein distance: how many single-letter edits turn one word into the other. */
    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j], current[j - 1]) + 1);
            }
            previous = current;
        }
        return previous[b.length()];
    }
}
