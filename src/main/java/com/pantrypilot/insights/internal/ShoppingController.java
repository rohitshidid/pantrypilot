package com.pantrypilot.insights.internal;

import com.pantrypilot.shared.Unit;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/insights/households/{household}")
class ShoppingController {
    record Threshold(String ingredient, Unit unit, BigDecimal minimum) {}
    private final ShoppingLists lists;

    ShoppingController(ShoppingLists lists) {
        this.lists = lists;
    }

    @PutMapping("/thresholds")
    void set(@PathVariable long household, @RequestParam long userId, @RequestBody Threshold rule) {
        lists.set(household, userId, rule.ingredient(), rule.unit(), rule.minimum());
    }

    @GetMapping("/shopping-list")
    List<ShoppingLists.Item> shopping(@PathVariable long household, @RequestParam long userId) {
        return lists.shopping(household, userId);
    }

    @DeleteMapping("/thresholds")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void remove(@PathVariable long household, @RequestParam long userId, @RequestParam String ingredient) {
        lists.remove(household, userId, ingredient);
    }
}
