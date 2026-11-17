package com.pantrypilot.core.stub;

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

/**
 * The old in-memory placeholder catalog, kept only as a test helper: the AI
 * module's unit tests build it with {@code new StubCatalog()} so they need no
 * database. The server itself uses the real {@code IngredientCatalog}.
 */
public class StubCatalog implements CatalogService, QuantityService {

    /**
     * @param gramsEach    average weight of one item, or null when it is not counted
     * @param gramsPerMl   density, or null when it is not measured by volume
     */
    private record Ingredient(long id, String name, BigDecimal gramsEach, BigDecimal gramsPerMl) {
    }

    private final Map<String, Ingredient> byName = new HashMap<>();
    private final Map<Long, Ingredient> byId = new HashMap<>();
    private final List<String> names = new ArrayList<>();

    public StubCatalog() {
        add("tomato", "120", null, "tomatoes", "roma tomato", "tamatar", "cherry tomato", "plum tomato");
        add("onion", "150", null, "onions", "red onion", "yellow onion", "white onion", "pyaz");
        add("garlic", "5", null, "garlic clove", "garlic cloves", "lehsun");
        add("potato", "170", null, "potatoes", "aloo", "russet potato");
        add("carrot", "60", null, "carrots", "gajar");
        add("bell pepper", "160", null, "bell peppers", "capsicum", "red bell pepper", "green bell pepper");
        add("spinach", null, null, "baby spinach", "palak");
        add("banana", "118", null, "bananas");
        add("apple", "180", null, "apples");
        add("lemon", "60", null, "lemons", "nimbu");
        add("lime", "65", null, "limes");
        add("cucumber", "300", null, "cucumbers");
        add("broccoli", "350", null, "broccoli crown");
        add("mushroom", "18", null, "mushrooms");
        add("avocado", "200", null, "avocados");
        add("ginger", null, null, "fresh ginger", "adrak");
        add("cilantro", null, null, "coriander leaves", "fresh coriander", "dhania");
        add("egg", "50", null, "eggs", "large eggs");
        add("milk", null, "1.03", "whole milk", "2% milk", "skim milk", "doodh");
        add("butter", null, "0.96", "unsalted butter", "salted butter");
        add("cream", null, "1.0", "heavy cream", "heavy whipping cream", "whipping cream");
        add("yogurt", null, "1.03", "plain yogurt", "greek yogurt", "curd", "dahi");
        add("cheese", null, null, "cheddar", "cheddar cheese", "mozzarella", "parmesan");
        add("paneer", null, null);
        add("chicken breast", "225", null, "chicken breasts", "boneless chicken breast", "chicken");
        add("ground beef", null, null, "minced beef", "beef mince");
        add("rice", null, "0.85", "basmati rice", "white rice", "long grain rice", "chawal");
        add("flour", null, "0.53", "all-purpose flour", "all purpose flour", "plain flour", "maida");
        add("sugar", null, "0.85", "granulated sugar", "white sugar");
        add("salt", null, "1.2", "kosher salt", "sea salt");
        add("black pepper", null, "0.5", "pepper", "ground black pepper");
        add("olive oil", null, "0.91", "extra virgin olive oil", "extra-virgin olive oil");
        add("vegetable oil", null, "0.92", "oil", "canola oil", "cooking oil");
        add("pasta", null, null, "spaghetti", "penne", "macaroni");
        add("bread", "30", null, "bread slice", "sandwich bread", "loaf of bread");
        add("beans", null, null, "black beans", "kidney beans", "organic beans", "pinto beans");
        add("lentils", null, "0.85", "red lentils", "dal", "daal");
        add("canned tomatoes", null, "1.0", "diced tomatoes", "crushed tomatoes", "tomato sauce");
        add("orange juice", null, "1.04", "oj");
        add("honey", null, "1.42");
        add("soy sauce", null, "1.2");
        add("cumin", null, "0.5", "ground cumin", "cumin seeds", "jeera");
    }

    @Override
    public Optional<CatalogMatch> resolve(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = normalize(name);
        Ingredient exact = byName.get(key);
        if (exact == null) {
            exact = byName.get(singular(key));
        }
        if (exact != null) {
            return Optional.of(new CatalogMatch(exact.id(), exact.name(), 1.0));
        }
        // The longest catalog name contained in the text as whole words: "large roma tomato" -> "roma tomato"
        String best = null;
        String padded = " " + singularWords(key) + " ";
        for (String candidate : names) {
            if (padded.contains(" " + candidate + " ") && (best == null || candidate.length() > best.length())) {
                best = candidate;
            }
        }
        if (best != null) {
            Ingredient found = byName.get(best);
            return Optional.of(new CatalogMatch(found.id(), found.name(), 0.7));
        }
        // One or two typos
        for (String candidate : names) {
            if (candidate.length() >= 5 && editDistance(key, candidate) <= 2) {
                Ingredient found = byName.get(candidate);
                return Optional.of(new CatalogMatch(found.id(), found.name(), 0.6));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<BigDecimal> convert(BigDecimal quantity, Unit from, Unit to, long ingredientId) {
        Ingredient ingredient = byId.get(ingredientId);
        if (ingredient == null || quantity == null) {
            return Optional.empty();
        }
        if (from == to) {
            return Optional.of(quantity);
        }
        BigDecimal grams = switch (from) {
            case G -> quantity;
            case COUNT -> ingredient.gramsEach() == null ? null : quantity.multiply(ingredient.gramsEach());
            case ML -> ingredient.gramsPerMl() == null ? null : quantity.multiply(ingredient.gramsPerMl());
        };
        if (grams == null) {
            return Optional.empty();
        }
        BigDecimal result = switch (to) {
            case G -> grams;
            case COUNT -> ingredient.gramsEach() == null ? null
                    : grams.divide(ingredient.gramsEach(), 3, RoundingMode.HALF_UP);
            case ML -> ingredient.gramsPerMl() == null ? null
                    : grams.divide(ingredient.gramsPerMl(), 3, RoundingMode.HALF_UP);
        };
        return Optional.ofNullable(result);
    }

    private void add(String name, String gramsEach, String gramsPerMl, String... aliases) {
        Ingredient ingredient = new Ingredient(
                byId.size() + 1L,
                name,
                gramsEach == null ? null : new BigDecimal(gramsEach),
                gramsPerMl == null ? null : new BigDecimal(gramsPerMl));
        byId.put(ingredient.id(), ingredient);
        register(name, ingredient);
        for (String alias : aliases) {
            register(alias, ingredient);
        }
    }

    private void register(String name, Ingredient ingredient) {
        String key = normalize(name);
        byName.put(key, ingredient);
        names.add(key);
    }

    private static String normalize(String s) {
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
