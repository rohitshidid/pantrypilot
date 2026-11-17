package com.pantrypilot.core.internal.inventory;

import com.pantrypilot.core.internal.catalog.Ingredient;
import com.pantrypilot.core.internal.catalog.IngredientKind;
import com.pantrypilot.core.internal.catalog.IngredientRepository;
import com.pantrypilot.shared.InventoryQuery;
import com.pantrypilot.shared.QuantityService;
import com.pantrypilot.shared.StockItem;
import com.pantrypilot.shared.Unit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The core module's answer to the insights module's question "what does this
 * household have right now": one line per ingredient, with the total given in
 * count, grams and millilitres at once.
 */
@Service
public class StockQuery implements InventoryQuery {

    private final LotRepository lots;
    private final IngredientRepository ingredients;
    private final QuantityService quantities;

    public StockQuery(LotRepository lots, IngredientRepository ingredients, QuantityService quantities) {
        this.lots = lots;
        this.ingredients = ingredients;
        this.quantities = quantities;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockItem> available(long householdId, long userId) {
        // The query already leaves out other members' private lots.
        Map<Long, List<Lot>> byIngredient = lots.visible(householdId, userId).stream()
                .collect(Collectors.groupingBy(Lot::getIngredientId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, Ingredient> known = ingredients.findAllById(byIngredient.keySet()).stream()
                .collect(Collectors.toMap(Ingredient::getId, Function.identity()));

        List<StockItem> stock = new ArrayList<>();
        byIngredient.forEach((ingredientId, held) -> {
            Ingredient ingredient = known.get(ingredientId);
            if (ingredient.getKind() != IngredientKind.INGREDIENT) {
                return;   // leftovers are meals, not something a recipe can use
            }
            LocalDate soonest = held.stream().map(Lot::getExpiresOn).filter(d -> d != null)
                    .min(LocalDate::compareTo).orElse(null);
            stock.add(new StockItem(ingredientId, ingredient.getName(),
                    total(held, Unit.COUNT), total(held, Unit.G), total(held, Unit.ML), soonest));
        });
        return stock;
    }

    /** Sum of the lots that can be expressed in that unit; null when none can (for example millilitres of onion). */
    private BigDecimal total(List<Lot> held, Unit unit) {
        BigDecimal sum = null;
        for (Lot lot : held) {
            Optional<BigDecimal> converted = quantities.convert(lot.getQuantity(), lot.getUnit(), unit, lot.getIngredientId());
            if (converted.isPresent()) {
                sum = sum == null ? converted.get() : sum.add(converted.get());
            }
        }
        return sum;
    }
}
