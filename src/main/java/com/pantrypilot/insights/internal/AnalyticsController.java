package com.pantrypilot.insights.internal;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AnalyticsController {
    private final Analytics analytics;
    private final Savings savings;

    AnalyticsController(Analytics analytics, Savings savings) {
        this.analytics = analytics;
        this.savings = savings;
    }

    @GetMapping("/api/insights/households/{household}/analytics")
    Analytics.Report report(@PathVariable long household, @RequestParam long userId,
                            @RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > 365) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose 1 to 365 days");
        return analytics.report(household, userId, days);
    }

    @GetMapping({"/api/insights/money-saved", "/api/stub/insights/money-saved"})
    Savings.Quote estimate(@RequestParam String dish, @RequestParam(defaultValue = "1") int servings,
                           @RequestParam(required = false) String city, @RequestParam BigDecimal ingredientCost) {
        try {
            return savings.quote(dish, servings, city, ingredientCost);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Menu price unavailable");
        }
    }

    @GetMapping("/api/insights/households/{household}/history")
    java.util.List<Analytics.HistoryRow> history(@PathVariable long household, @RequestParam long userId) {
        return analytics.history(household, userId);
    }
}
