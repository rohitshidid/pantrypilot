package com.pantrypilot.insights.internal;

import com.pantrypilot.shared.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/insights/households/{household}/cook-now")
class CookNow {
    record Suggestion(String title, String url, int servings, List<String> missing) {}
    private static RecipeIngredient ingredient(String name, int quantity, Unit unit) {
        return new RecipeIngredient(name, name, BigDecimal.valueOf(quantity), unit);
    }
    private static final List<Recipe> STARTERS = List.of(
            new Recipe("Scrambled eggs", null, 1, List.of(ingredient("egg", 2, Unit.COUNT))),
            new Recipe("Tomato pasta", null, 1, List.of(ingredient("pasta", 100, Unit.G), ingredient("tomato", 2, Unit.COUNT))),
            new Recipe("Egg fried rice", null, 1, List.of(ingredient("rice", 75, Unit.G), ingredient("egg", 1, Unit.COUNT))));

    private final HouseholdPantry pantry;
    private final InventoryQuery inventory;
    private final CatalogService catalog;
    private final QuantityService quantities;
    private final RecipeExtractor recipes;

    CookNow(HouseholdPantry pantry, InventoryQuery inventory, CatalogService catalog,
            QuantityService quantities, RecipeExtractor recipes) {
        this.pantry = pantry;
        this.inventory = inventory;
        this.catalog = catalog;
        this.quantities = quantities;
        this.recipes = recipes;
    }

    @GetMapping
    List<Suggestion> suggestions(@PathVariable long household, @RequestParam long userId,
                                 @RequestParam(required = false) String url) {
        pantry.visible(household, userId);
        var stock = inventory.available(household, userId);
        if (url == null) {
            return STARTERS.stream().map(recipe -> check(recipe, stock)).filter(r -> r.missing().isEmpty()).toList();
        }
        try {
            return List.of(check(recipes.extract(url), stock));
        } catch (RecipeExtractionException invalid) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, invalid.getMessage());
        }
    }

    Suggestion check(Recipe recipe, List<StockItem> stock) {
        record Need(Unit unit, BigDecimal amount) {}
        Map<Long, Need> totals = new HashMap<>();
        Map<Long, StockItem> byId = new HashMap<>();
        stock.forEach(item -> byId.put(item.ingredientId(), item));
        List<String> missing = new ArrayList<>();
        for (var line : recipe.ingredients()) {
            var match = catalog.resolve(line.name());
            if (match.isEmpty() || !byId.containsKey(match.get().ingredientId())) {
                missing.add(line.name());
                continue;
            }
            if (line.quantity() == null) continue; // e.g. salt to taste only requires presence
            long id = match.get().ingredientId();
            var previous = totals.getOrDefault(id, new Need(line.unit(), BigDecimal.ZERO));
            var amount = quantities.convert(line.quantity(), line.unit(), previous.unit(), id);
            if (amount.isEmpty()) {
                missing.add(line.name());
                continue;
            }
            totals.put(id, new Need(previous.unit(), previous.amount().add(amount.get())));
        }
        totals.forEach((id, need) -> {
            var item = byId.get(id);
            BigDecimal have = switch (need.unit()) {
                case COUNT -> item.count();
                case G -> item.grams();
                case ML -> item.millilitres();
            };
            if (have == null || have.compareTo(need.amount()) < 0) missing.add(item.name());
        });
        return new Suggestion(recipe.title(), recipe.url(), recipe.servings() == null ? 1 : recipe.servings(), missing);
    }
}
