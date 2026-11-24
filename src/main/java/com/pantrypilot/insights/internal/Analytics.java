package com.pantrypilot.insights.internal;

import com.pantrypilot.shared.Unit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.springframework.stereotype.Service;

@Service
class Analytics {
    record Entry(long lotId, String name, String type, BigDecimal delta, Unit unit) {}
    record Transaction(long id, long userId, String kind, String dishName, Integer servings, Instant at,
                       Long undoneByTxnId, List<Entry> entries) {}
    record HistoryRow(long id, String kind, Instant at, Long undoneByTxnId, List<Entry> entries) {}
    record Measure(String name, Unit unit) {}
    record Usage(String name, Unit unit, BigDecimal quantity, BigDecimal dailyAverage) {}
    record Waste(String name, Unit unit, BigDecimal quantity) {}
    record Meal(String dish, int servings, BigDecimal ingredientCost, Savings.Quote quote) {}
    record Report(int days, boolean historyLimited, List<Usage> usage, List<Waste> waste, List<Meal> meals,
                  BigDecimal savings, BigDecimal wasteCost, int unpricedWasteLines, int unpricedMeals) {}

    private final HouseholdPantry pantry;
    private final ReceiptLots receipts;
    private final Savings savings;

    Analytics(HouseholdPantry pantry, ReceiptLots receipts, Savings savings) {
        this.pantry = pantry;
        this.receipts = receipts;
        this.savings = savings;
    }

    Report report(long household, long viewer, int days) {
        var prices = receipts.observe(household, viewer, pantry.visible(household, viewer));
        var history = pantry.history(household, viewer);
        Instant start = Instant.now().minus(days, ChronoUnit.DAYS);
        Map<Measure, BigDecimal> used = new LinkedHashMap<>();
        Map<Measure, BigDecimal> discarded = new LinkedHashMap<>();
        List<Meal> meals = new ArrayList<>();
        BigDecimal wasteCost = BigDecimal.ZERO;
        BigDecimal saved = BigDecimal.ZERO;
        int missingWaste = 0;
        int missingMeals = 0;
        for (var txn : history) {
            if (txn.at().isBefore(start) || txn.undoneByTxnId() != null) continue;
            BigDecimal cookCost = BigDecimal.ZERO;
            boolean complete = true;
            boolean visible = true;
            boolean cooked = false;
            for (var entry : txn.entries()) {
                boolean consumed = "CONSUME".equals(entry.type());
                if (!consumed && !"DISCARD".equals(entry.type())) continue;
                var lot = prices.get(entry.lotId());
                if (!maySee(lot, txn, viewer)) {
                    visible = false;
                    continue;
                }
                var quantity = entry.delta().abs();
                (consumed ? used : discarded).merge(new Measure(entry.name(), entry.unit()), quantity, BigDecimal::add);
                BigDecimal cost = lot == null || lot.unitPrice() == null ? null : lot.unitPrice().multiply(quantity);
                if (consumed) {
                    cooked = true;
                    if (cost == null) complete = false;
                    else cookCost = cookCost.add(cost);
                } else if (cost == null) {
                    missingWaste++;
                } else {
                    wasteCost = wasteCost.add(cost);
                }
            }
            if (cooked && visible && txn.dishName() != null && !txn.dishName().isBlank()) {
                int servings = txn.servings() == null || txn.servings() < 1 ? 1 : txn.servings();
                BigDecimal cost = complete ? cents(cookCost) : null;
                Savings.Quote quote = null;
                if (complete) {
                    try {
                        quote = savings.quote(txn.dishName(), servings, null, cost);
                    } catch (RuntimeException unavailable) {
                        // Keep the cooked dish and its receipt cost when a menu price is unavailable.
                    }
                }
                if (quote == null) missingMeals++;
                else saved = saved.add(quote.saved());
                meals.add(new Meal(txn.dishName(), servings, cost, quote));
            }
        }
        var usage = used.entrySet().stream().map(entry -> new Usage(entry.getKey().name(), entry.getKey().unit(),
                entry.getValue(), entry.getValue().divide(BigDecimal.valueOf(days), 4, RoundingMode.HALF_UP))).toList();
        var waste = discarded.entrySet().stream().map(entry -> new Waste(entry.getKey().name(), entry.getKey().unit(),
                entry.getValue())).toList();
        boolean limited = history.size() == 200 && !history.getLast().at().isBefore(start);
        return new Report(days, limited, usage, waste, meals, cents(saved), cents(wasteCost), missingWaste, missingMeals);
    }

    List<HistoryRow> history(long household, long viewer) {
        var prices = receipts.observe(household, viewer, pantry.visible(household, viewer));
        List<HistoryRow> rows = new ArrayList<>();
        for (var txn : pantry.history(household, viewer)) {
            var visible = txn.entries().stream().filter(entry -> maySee(prices.get(entry.lotId()), txn, viewer)).toList();
            if (!visible.isEmpty()) rows.add(new HistoryRow(txn.id(), txn.kind(), txn.at(), txn.undoneByTxnId(), visible));
        }
        return rows;
    }

    private static boolean maySee(ReceiptLots.Receipt lot, Transaction txn, long viewer) {
        if (lot == null) return txn.userId() == viewer && !"UNDO".equals(txn.kind());
        return lot.owner() == null || lot.owner() == viewer;
    }

    private static BigDecimal cents(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
