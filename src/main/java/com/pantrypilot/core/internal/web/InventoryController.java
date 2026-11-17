package com.pantrypilot.core.internal.web;

import com.pantrypilot.core.internal.CoreException;
import com.pantrypilot.core.internal.catalog.StorageLocation;
import com.pantrypilot.core.internal.inventory.InventoryService;
import com.pantrypilot.core.internal.inventory.InventoryService.ChangeResult;
import com.pantrypilot.core.internal.inventory.InventoryService.LotView;
import com.pantrypilot.core.internal.inventory.InventoryService.TxnView;
import com.pantrypilot.core.internal.inventory.InventoryWriter.InLine;
import com.pantrypilot.core.internal.inventory.InventoryWriter.OutLine;
import com.pantrypilot.shared.InventoryQuery;
import com.pantrypilot.shared.StockItem;
import com.pantrypilot.shared.Unit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The pantry of one household: what is there, manual entry, discarding, leftovers, history and undo. */
@RestController
@RequestMapping("/api/core/households/{householdId}")
public class InventoryController {

    /** @param personal true keeps the item private to the user who added it */
    record InLineBody(Long ingredientId, String name, BigDecimal quantity, Unit unit, BigDecimal unitPrice,
            StorageLocation location, LocalDate expiresOn, boolean personal) {
    }

    record StockInBody(List<InLineBody> lines) {
    }

    record OutLineBody(Long ingredientId, String name, BigDecimal quantity, Unit unit) {
    }

    /** @param strict null counts as true */
    record ConsumeBody(List<OutLineBody> lines, Boolean strict, String dishName, Integer servings) {
    }

    record DiscardBody(BigDecimal quantity, String reason) {
    }

    record LeftoverBody(String name, BigDecimal servings, StorageLocation location, LocalDate expiresOn,
            boolean personal) {
    }

    private final InventoryService inventory;
    private final InventoryQuery stock;
    private final LineResolver resolver;

    public InventoryController(InventoryService inventory, InventoryQuery stock, LineResolver resolver) {
        this.inventory = inventory;
        this.stock = stock;
        this.resolver = resolver;
    }

    /** Every lot, one row per purchase. */
    @GetMapping("/pantry")
    List<LotView> pantry(@PathVariable long householdId, @RequestParam long userId) {
        return inventory.pantry(householdId, userId);
    }

    /** One row per ingredient, totalled in count, grams and millilitres. */
    @GetMapping("/stock")
    List<StockItem> stock(@PathVariable long householdId, @RequestParam long userId) {
        inventory.pantry(householdId, userId);   // membership check
        return stock.available(householdId, userId);
    }

    @GetMapping("/expiring")
    List<LotView> expiring(@PathVariable long householdId, @RequestParam long userId,
            @RequestParam(defaultValue = "3") int days) {
        return inventory.expiring(householdId, userId, days);
    }

    @GetMapping("/history")
    List<TxnView> history(@PathVariable long householdId, @RequestParam long userId,
            @RequestParam(defaultValue = "50") int limit) {
        return inventory.history(householdId, userId, limit);
    }

    @PostMapping("/stock-in")
    ChangeResult stockIn(@PathVariable long householdId, @RequestParam long userId, @RequestBody StockInBody body) {
        List<InLine> lines = required(body.lines()).stream()
                .map(l -> new InLine(resolver.ingredientId(l.ingredientId(), l.name()), l.quantity(), l.unit(),
                        l.unitPrice(), l.location(), l.expiresOn(), l.personal()))
                .toList();
        return inventory.stockIn(householdId, userId, lines);
    }

    @PostMapping("/consume")
    ChangeResult consume(@PathVariable long householdId, @RequestParam long userId, @RequestBody ConsumeBody body) {
        List<OutLine> lines = required(body.lines()).stream()
                .map(l -> new OutLine(resolver.ingredientId(l.ingredientId(), l.name()), l.quantity(), l.unit()))
                .toList();
        return inventory.consume(householdId, userId, lines, body.strict() == null || body.strict(),
                body.dishName(), body.servings());
    }

    @PostMapping("/lots/{lotId}/discard")
    ChangeResult discard(@PathVariable long householdId, @PathVariable long lotId, @RequestParam long userId,
            @RequestBody(required = false) DiscardBody body) {
        return inventory.discard(householdId, userId, lotId, body == null ? null : body.quantity(),
                body == null ? null : body.reason());
    }

    @PostMapping("/leftovers")
    ChangeResult leftover(@PathVariable long householdId, @RequestParam long userId, @RequestBody LeftoverBody body) {
        return inventory.saveLeftover(householdId, userId, body.name(), body.servings(), body.location(),
                body.expiresOn(), body.personal());
    }

    @PostMapping("/changes/{txnId}/undo")
    ChangeResult undo(@PathVariable long householdId, @PathVariable long txnId, @RequestParam long userId) {
        return inventory.undo(householdId, userId, txnId);
    }

    private static <T> List<T> required(List<T> lines) {
        if (lines == null || lines.isEmpty()) {
            throw CoreException.invalid("At least one line is required");
        }
        return lines;
    }
}
