package com.pantrypilot.insights.internal;

import com.pantrypilot.shared.PriceEstimate;
import com.pantrypilot.shared.PriceEstimator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
class Savings {
    record Quote(PriceEstimate estimate, BigDecimal orderingOutWithFeesTaxAndTip, BigDecimal ingredientCost,
                 BigDecimal saved) {}
    private final PriceEstimator prices;

    Savings(PriceEstimator prices) {
        this.prices = prices;
    }

    Quote quote(String dish, int servings, String city, BigDecimal ingredientCost) {
        if (ingredientCost.signum() < 0) throw new IllegalArgumentException("Ingredient cost must be nonnegative");
        var estimate = prices.estimate(dish, servings, city);
        var order = estimate.total().multiply(new BigDecimal("1.38875")).setScale(2, RoundingMode.HALF_UP);
        return new Quote(estimate, order, ingredientCost, order.subtract(ingredientCost).setScale(2, RoundingMode.HALF_UP));
    }
}
