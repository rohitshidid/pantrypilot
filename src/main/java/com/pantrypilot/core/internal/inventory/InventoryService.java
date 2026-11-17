package com.pantrypilot.core.internal.inventory;

import com.pantrypilot.core.internal.ConflictRetry;
import com.pantrypilot.core.internal.catalog.Ingredient;
import com.pantrypilot.core.internal.catalog.IngredientKind;
import com.pantrypilot.core.internal.catalog.IngredientRepository;
import com.pantrypilot.core.internal.catalog.StorageLocation;
import com.pantrypilot.core.internal.household.HouseholdService;
import com.pantrypilot.core.internal.household.Membership;
import com.pantrypilot.core.internal.household.Role;
import com.pantrypilot.core.internal.inventory.InventoryWriter.Applied;
import com.pantrypilot.core.internal.inventory.InventoryWriter.InLine;
import com.pantrypilot.core.internal.inventory.InventoryWriter.OutLine;
import com.pantrypilot.core.internal.inventory.InventoryWriter.Shortfall;
import com.pantrypilot.shared.Unit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * What the REST layer calls. It deliberately has no {@code @Transactional}:
 * each change is three steps that must stay outside the transaction.
 *
 * <ol>
 *   <li>check the user belongs to the household,</li>
 *   <li>run the {@link InventoryWriter} transaction, again from the start if it
 *       lost a race with another member ({@link ConflictRetry}),</li>
 *   <li>after it committed, publish the events it caused.</li>
 * </ol>
 */
@Service
public class InventoryService {

    public record ChangeResult(long txnId, BigDecimal cost, List<Shortfall> shortfalls) {
    }

    /** @param daysLeft negative when past its date, null when it has none; @param personal private to the viewer */
    public record LotView(long lotId, long ingredientId, String name, IngredientKind kind, BigDecimal quantity,
            Unit unit, BigDecimal unitPrice, StorageLocation location, LocalDate expiresOn, Long daysLeft,
            boolean personal) {
    }

    public record EntryView(long lotId, long ingredientId, String name, EntryType type, BigDecimal delta, Unit unit) {
    }

    /** @param undoneByTxnId the undo that reversed this change, or null */
    public record TxnView(long id, TxnKind kind, long userId, String userName, String dishName, Integer servings,
            String note, Instant at, Long reversesTxnId, Long undoneByTxnId, List<EntryView> entries) {
    }

    private final InventoryWriter writer;
    private final HouseholdService households;
    private final ConflictRetry retry;
    private final ApplicationEventPublisher publisher;
    private final LotRepository lots;
    private final InventoryTxnRepository txns;
    private final LedgerEntryRepository ledger;
    private final IngredientRepository ingredients;

    public InventoryService(InventoryWriter writer, HouseholdService households, ConflictRetry retry,
            ApplicationEventPublisher publisher, LotRepository lots, InventoryTxnRepository txns,
            LedgerEntryRepository ledger, IngredientRepository ingredients) {
        this.writer = writer;
        this.households = households;
        this.retry = retry;
        this.publisher = publisher;
        this.lots = lots;
        this.txns = txns;
        this.ledger = ledger;
        this.ingredients = ingredients;
    }

    // ---- changes --------------------------------------------------------------

    public ChangeResult stockIn(long householdId, long userId, List<InLine> lines) {
        households.requireMember(householdId, userId);
        return apply(() -> writer.stockIn(householdId, userId, TxnKind.MANUAL_IN, null, null, lines));
    }

    public ChangeResult consume(long householdId, long userId, List<OutLine> lines, boolean strict,
            String dishName, Integer servings) {
        households.requireMember(householdId, userId);
        return apply(() -> writer.takeOut(householdId, userId, TxnKind.MANUAL_OUT, EntryType.CONSUME, null,
                dishName, servings, null, lines, strict));
    }

    public ChangeResult discard(long householdId, long userId, long lotId, BigDecimal quantity, String reason) {
        households.requireMember(householdId, userId);
        return apply(() -> writer.discardLot(householdId, userId, lotId, quantity, reason));
    }

    public ChangeResult saveLeftover(long householdId, long userId, String name, BigDecimal servings,
            StorageLocation location, LocalDate expiresOn, boolean personal) {
        households.requireMember(householdId, userId);
        return apply(() -> writer.saveLeftover(householdId, userId, name, servings, location, expiresOn, personal));
    }

    public ChangeResult undo(long householdId, long userId, long txnId) {
        Membership membership = households.requireMember(householdId, userId);
        boolean admin = membership.getRole() == Role.ADMIN;
        return apply(() -> writer.undo(householdId, userId, txnId, admin));
    }

    private ChangeResult apply(Supplier<Applied> transaction) {
        Applied applied = retry.run(transaction);
        applied.events().forEach(publisher::publishEvent);
        return new ChangeResult(applied.txnId(), applied.cost(), applied.shortfalls());
    }

    // ---- reading --------------------------------------------------------------

    /** Every lot the user may see, soonest expiry first. Other members' private items are left out. */
    public List<LotView> pantry(long householdId, long userId) {
        households.requireMember(householdId, userId);
        List<Lot> visible = lots.visible(householdId, userId);
        Map<Long, Ingredient> byId = ingredientsOf(visible.stream().map(Lot::getIngredientId).toList());
        LocalDate today = LocalDate.now();
        return visible.stream().map(lot -> {
            Ingredient ingredient = byId.get(lot.getIngredientId());
            Long daysLeft = lot.getExpiresOn() == null ? null : ChronoUnit.DAYS.between(today, lot.getExpiresOn());
            return new LotView(lot.getId(), ingredient.getId(), ingredient.getName(), ingredient.getKind(),
                    lot.getQuantity(), lot.getUnit(), lot.getUnitPrice(), lot.getLocation(), lot.getExpiresOn(),
                    daysLeft, lot.getPrivateOwnerId() != null);
        }).toList();
    }

    /** Lots that expire within {@code days} days, including ones already past their date. */
    public List<LotView> expiring(long householdId, long userId, int days) {
        return pantry(householdId, userId).stream()
                .filter(lot -> lot.daysLeft() != null && lot.daysLeft() <= days)
                .toList();
    }

    /** The audit log: the most recent changes with who made them and exactly what moved. */
    public List<TxnView> history(long householdId, long userId, int limit) {
        households.requireMember(householdId, userId);
        List<InventoryTxn> recent = txns.findByHouseholdIdOrderByIdDesc(householdId,
                PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
        List<Long> ids = recent.stream().map(InventoryTxn::getId).toList();
        Map<Long, List<LedgerEntry>> entries = ledger.findByTxnIdInOrderById(ids).stream()
                .collect(Collectors.groupingBy(LedgerEntry::getTxnId));
        Map<Long, Long> undoneBy = new HashMap<>();
        txns.findByReversesTxnIdIn(ids).forEach(undo -> undoneBy.put(undo.getReversesTxnId(), undo.getId()));
        Map<Long, String> userNames = households.displayNames(recent.stream().map(InventoryTxn::getUserId).toList());
        Map<Long, Ingredient> byId = ingredientsOf(entries.values().stream()
                .flatMap(List::stream).map(LedgerEntry::getIngredientId).toList());

        List<TxnView> views = new ArrayList<>();
        for (InventoryTxn txn : recent) {
            List<EntryView> lines = entries.getOrDefault(txn.getId(), List.of()).stream()
                    .map(e -> new EntryView(e.getLotId(), e.getIngredientId(), byId.get(e.getIngredientId()).getName(),
                            e.getType(), e.getDelta(), e.getUnit()))
                    .toList();
            views.add(new TxnView(txn.getId(), txn.getKind(), txn.getUserId(), userNames.get(txn.getUserId()),
                    txn.getDishName(), txn.getServings(), txn.getNote(), txn.getCreatedAt(), txn.getReversesTxnId(),
                    undoneBy.get(txn.getId()), lines));
        }
        return views;
    }

    private Map<Long, Ingredient> ingredientsOf(List<Long> ids) {
        return ingredients.findAllById(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(Ingredient::getId, Function.identity()));
    }
}
