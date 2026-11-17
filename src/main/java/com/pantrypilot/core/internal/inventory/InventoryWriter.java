package com.pantrypilot.core.internal.inventory;

import com.pantrypilot.core.internal.CoreException;
import com.pantrypilot.core.internal.catalog.Ingredient;
import com.pantrypilot.core.internal.catalog.IngredientKind;
import com.pantrypilot.core.internal.catalog.IngredientRepository;
import com.pantrypilot.core.internal.catalog.StorageLocation;
import com.pantrypilot.shared.QuantityService;
import com.pantrypilot.shared.Unit;
import com.pantrypilot.shared.events.DishCooked;
import com.pantrypilot.shared.events.InventoryChanged;
import com.pantrypilot.shared.events.ItemConsumed;
import com.pantrypilot.shared.events.ItemDiscarded;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only class that changes the inventory. Every public method is one
 * database transaction: all of its lots and ledger rows are written, or, if
 * anything throws, none are.
 *
 * <p>{@code @Transactional} with the default propagation means "join the
 * caller's transaction if there is one, otherwise start one". So when the
 * confirmation queue calls {@link #takeOut} from inside its own transaction,
 * the deductions and the proposal's CONFIRMED state commit or roll back as one.
 *
 * <p>Nothing is published from here. Each method returns the domain events it
 * caused, and the caller publishes them only after the commit succeeded, so the
 * insights module never hears about a change that was rolled back.
 */
@Service
@Transactional
public class InventoryWriter {

    private static final int SCALE = 3;
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("10000000");
    private static final int LEFTOVER_FRIDGE_DAYS = 4;
    private static final int LEFTOVER_FREEZER_DAYS = 90;

    /** @param location null for the ingredient's usual place; @param expiresOn null to work it out from shelf life */
    public record InLine(long ingredientId, BigDecimal quantity, Unit unit, BigDecimal unitPrice,
            StorageLocation location, LocalDate expiresOn, boolean personal) {
    }

    public record OutLine(long ingredientId, BigDecimal quantity, Unit unit) {
    }

    /** An amount that was asked for but was not in stock (only reported when not strict). */
    public record Shortfall(long ingredientId, String name, BigDecimal missing, Unit unit) {
    }

    /** @param cost what the removed stock cost at its receipt prices; zero for stock-in */
    public record Applied(long txnId, BigDecimal cost, List<Shortfall> shortfalls, List<Object> events) {
    }

    private final LotRepository lots;
    private final InventoryTxnRepository txns;
    private final LedgerEntryRepository ledger;
    private final CookEventRepository cookEvents;
    private final IngredientRepository ingredients;
    private final QuantityService quantities;

    public InventoryWriter(LotRepository lots, InventoryTxnRepository txns, LedgerEntryRepository ledger,
            CookEventRepository cookEvents, IngredientRepository ingredients, QuantityService quantities) {
        this.lots = lots;
        this.txns = txns;
        this.ledger = ledger;
        this.cookEvents = cookEvents;
        this.ingredients = ingredients;
        this.quantities = quantities;
    }

    // ---- stock in -------------------------------------------------------------

    /** Creates one lot per line, each with its own price and expiry. */
    public Applied stockIn(long householdId, long userId, TxnKind kind, Long pendingChangeId, String note,
            List<InLine> lines) {
        requireLines(lines);
        InventoryTxn txn = txns.save(new InventoryTxn(householdId, userId, kind, pendingChangeId, null, null, note, null));
        Set<Long> touched = new LinkedHashSet<>();
        for (InLine line : lines) {
            Ingredient ingredient = requireIngredient(householdId, line.ingredientId());
            BigDecimal quantity = amount(line.quantity(), ingredient.getName());
            requireUnit(line.unit(), ingredient.getName());
            if (line.unitPrice() != null && line.unitPrice().signum() < 0) {
                throw CoreException.invalid("The price of " + ingredient.getName() + " cannot be negative");
            }
            StorageLocation location = line.location() != null ? line.location() : ingredient.getDefaultLocation();
            Lot lot = lots.save(new Lot(householdId, ingredient.getId(), quantity, line.unit(), line.unitPrice(),
                    location, expiry(line.expiresOn(), ingredient, location), line.personal() ? userId : null));
            ledger.save(new LedgerEntry(txn, lot, EntryType.STOCK_IN, quantity));
            touched.add(ingredient.getId());
        }
        return new Applied(txn.getId(), BigDecimal.ZERO.setScale(2), List.of(), changed(householdId, touched, txn));
    }

    /** A cooked dish kept for later: stored as servings, with its own short expiry. */
    public Applied saveLeftover(long householdId, long userId, String name, BigDecimal servings,
            StorageLocation location, LocalDate expiresOn, boolean personal) {
        if (name == null || name.isBlank()) {
            throw CoreException.invalid("A name for the leftover is required");
        }
        String dish = name.strip();
        if (dish.length() > 100) {
            throw CoreException.invalid("The name can be at most 100 characters");
        }
        Ingredient leftover = ingredients.findByHouseholdIdAndNameIgnoreCase(householdId, dish)
                .orElseGet(() -> ingredients.save(new Ingredient(householdId, dish, IngredientKind.LEFTOVER, null, null,
                        StorageLocation.FRIDGE, null, LEFTOVER_FRIDGE_DAYS, LEFTOVER_FREEZER_DAYS)));
        return stockIn(householdId, userId, TxnKind.LEFTOVER, null, "Leftover: " + dish,
                List.of(new InLine(leftover.getId(), servings, Unit.COUNT, null, location, expiresOn, personal)));
    }

    // ---- stock out ------------------------------------------------------------

    /**
     * Removes each line from the lots the user may use, soonest expiry first.
     *
     * @param type     CONSUME or DISCARD
     * @param strict   true: a line that cannot be fully covered fails the whole call and nothing is changed.
     *                 false: take what is there and report the rest as shortfalls.
     * @param dishName when not null, a cook event is recorded with the real ingredient cost
     */
    public Applied takeOut(long householdId, long userId, TxnKind kind, EntryType type, Long pendingChangeId,
            String dishName, Integer servings, String note, List<OutLine> lines, boolean strict) {
        requireLines(lines);
        InventoryTxn txn = txns.save(
                new InventoryTxn(householdId, userId, kind, pendingChangeId, dishName, servings, note, null));
        Instant at = txn.getCreatedAt();
        BigDecimal cost = BigDecimal.ZERO;
        boolean anythingTaken = false;
        Set<Long> touched = new LinkedHashSet<>();
        List<Shortfall> shortfalls = new ArrayList<>();
        List<Object> events = new ArrayList<>();

        for (OutLine line : lines) {
            Ingredient ingredient = requireIngredient(householdId, line.ingredientId());
            BigDecimal wanted = amount(line.quantity(), ingredient.getName());
            requireUnit(line.unit(), ingredient.getName());
            BigDecimal remaining = wanted;
            boolean unitMismatch = false;

            for (Lot lot : lots.visibleOf(householdId, userId, ingredient.getId())) {
                if (remaining.signum() == 0) {
                    break;
                }
                // How much of THIS lot, in the lot's own unit, covers what is still needed?
                Optional<BigDecimal> needed = quantities.convert(remaining, line.unit(), lot.getUnit(), ingredient.getId());
                if (needed.isEmpty()) {
                    unitMismatch = true;   // e.g. the recipe says 200 ml and this lot is counted
                    continue;
                }
                BigDecimal need = needed.get().setScale(SCALE, RoundingMode.HALF_UP);
                BigDecimal take;
                BigDecimal covered;      // what that take is worth in the line's unit
                if (lot.getQuantity().compareTo(need) >= 0) {
                    take = need;
                    covered = remaining;
                } else {
                    take = lot.getQuantity();
                    covered = quantities.convert(take, lot.getUnit(), line.unit(), ingredient.getId())
                            .orElse(BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP).min(remaining);
                }
                if (take.signum() > 0) {
                    lot.change(take.negate());
                    ledger.save(new LedgerEntry(txn, lot, type, take.negate()));
                    if (lot.getUnitPrice() != null) {
                        cost = cost.add(take.multiply(lot.getUnitPrice()));
                    }
                    anythingTaken = true;
                    touched.add(ingredient.getId());
                }
                remaining = remaining.subtract(covered);
            }

            if (remaining.signum() > 0) {
                if (strict) {
                    // Thrown from inside the transaction: every earlier line of this call is rolled back too.
                    throw CoreException.conflict("Not enough " + ingredient.getName() + ": short by "
                            + remaining.stripTrailingZeros().toPlainString() + " " + line.unit()
                            + (unitMismatch ? " (some of it is stored in a unit that cannot be converted)" : ""));
                }
                shortfalls.add(new Shortfall(ingredient.getId(), ingredient.getName(), remaining, line.unit()));
            }
            BigDecimal taken = wanted.subtract(remaining);
            if (taken.signum() > 0) {
                events.add(type == EntryType.DISCARD
                        ? new ItemDiscarded(householdId, userId, ingredient.getId(), taken, line.unit(), note, at)
                        : new ItemConsumed(householdId, userId, ingredient.getId(), taken, line.unit(), at));
            }
        }
        if (!anythingTaken) {
            throw CoreException.conflict("None of that is in stock, so nothing was changed");
        }
        cost = cost.setScale(2, RoundingMode.HALF_UP);
        if (dishName != null && !dishName.isBlank() && type == EntryType.CONSUME) {
            int made = servings == null || servings < 1 ? 1 : servings;
            cookEvents.save(new CookEvent(txn, dishName, made, cost));
            events.add(new DishCooked(householdId, userId, dishName, made, cost, at));
        }
        events.addAll(changed(householdId, touched, txn));
        return new Applied(txn.getId(), cost, List.copyOf(shortfalls), events);
    }

    /** Throws away part or all of one specific lot, for example the one that went off. */
    public Applied discardLot(long householdId, long userId, long lotId, BigDecimal quantity, String reason) {
        Lot lot = lots.findById(lotId)
                .filter(l -> l.getHouseholdId() == householdId)
                .filter(l -> l.getPrivateOwnerId() == null || l.getPrivateOwnerId() == userId)
                .orElseThrow(() -> CoreException.notFound("No such item in this household"));
        Ingredient ingredient = ingredients.findById(lot.getIngredientId()).orElseThrow();
        BigDecimal amount = quantity == null ? lot.getQuantity() : amount(quantity, ingredient.getName());
        if (lot.getQuantity().signum() == 0) {
            throw CoreException.conflict("That item is already used up");
        }
        if (amount.compareTo(lot.getQuantity()) > 0) {
            throw CoreException.invalid("Only " + lot.getQuantity().stripTrailingZeros().toPlainString() + " "
                    + lot.getUnit() + " of that is left");
        }
        String why = reason == null || reason.isBlank() ? "discarded" : truncate(reason.strip(), 300);
        InventoryTxn txn = txns.save(new InventoryTxn(householdId, userId, TxnKind.DISCARD, null, null, null, why, null));
        lot.change(amount.negate());
        ledger.save(new LedgerEntry(txn, lot, EntryType.DISCARD, amount.negate()));
        BigDecimal cost = lot.getUnitPrice() == null ? BigDecimal.ZERO : amount.multiply(lot.getUnitPrice());
        List<Object> events = new ArrayList<>();
        events.add(new ItemDiscarded(householdId, userId, ingredient.getId(), amount, lot.getUnit(), why, txn.getCreatedAt()));
        events.addAll(changed(householdId, Set.of(ingredient.getId()), txn));
        return new Applied(txn.getId(), cost.setScale(2, RoundingMode.HALF_UP), List.of(), events);
    }

    // ---- undo -----------------------------------------------------------------

    /**
     * Undo as a compensating transaction: the original rows are left exactly as
     * they are, and a new transaction is appended whose ledger rows are the same
     * amounts with the opposite sign. History therefore shows both the change
     * and its undo.
     *
     * @param admin admins may undo anyone's change; members only their own
     */
    public Applied undo(long householdId, long userId, long txnId, boolean admin) {
        InventoryTxn original = txns.findById(txnId)
                .filter(t -> t.getHouseholdId() == householdId)
                .orElseThrow(() -> CoreException.notFound("No such change in this household"));
        if (original.getKind() == TxnKind.UNDO) {
            throw CoreException.invalid("An undo cannot itself be undone");
        }
        if (!admin && original.getUserId() != userId) {
            throw CoreException.forbidden("Only the person who made that change, or an admin, can undo it");
        }
        if (txns.existsByReversesTxnId(txnId)) {
            throw CoreException.conflict("That change was already undone");
        }
        InventoryTxn undo;
        try {
            // Flushed at once so that, if two people press undo together, the database's
            // unique constraint on reverses_txn_id refuses the second one right here.
            undo = txns.saveAndFlush(new InventoryTxn(householdId, userId, TxnKind.UNDO, original.getPendingChangeId(),
                    original.getDishName(), original.getServings(), "Undo of change #" + txnId, txnId));
        } catch (DataIntegrityViolationException e) {
            throw CoreException.conflict("That change was already undone");
        }
        Set<Long> touched = new LinkedHashSet<>();
        for (LedgerEntry entry : ledger.findByTxnIdOrderById(txnId)) {
            Lot lot = lots.findById(entry.getLotId()).orElseThrow();
            BigDecimal reverse = entry.getDelta().negate();
            if (lot.getQuantity().add(reverse).signum() < 0) {
                String name = ingredients.findById(lot.getIngredientId()).map(Ingredient::getName).orElse("that item");
                throw CoreException.conflict("Cannot undo: some of the " + name
                        + " added by that change has already been used");
            }
            lot.change(reverse);
            ledger.save(new LedgerEntry(undo, lot, EntryType.UNDO, reverse));
            touched.add(lot.getIngredientId());
        }
        return new Applied(undo.getId(), BigDecimal.ZERO.setScale(2), List.of(), changed(householdId, touched, undo));
    }

    // ---- helpers --------------------------------------------------------------

    private Ingredient requireIngredient(long householdId, long ingredientId) {
        return ingredients.findById(ingredientId)
                .filter(i -> i.getHouseholdId() == Ingredient.SHARED || i.getHouseholdId() == householdId)
                .orElseThrow(() -> CoreException.invalid("Ingredient " + ingredientId + " is not in the catalog"));
    }

    /** Shelf life counts from today, in the place the item is stored. */
    private static LocalDate expiry(LocalDate given, Ingredient ingredient, StorageLocation location) {
        if (given != null) {
            return given;
        }
        Integer days = ingredient.shelfDays(location);
        return days == null ? null : LocalDate.now().plusDays(days);
    }

    private static BigDecimal amount(BigDecimal quantity, String name) {
        if (quantity == null || quantity.signum() <= 0) {
            throw CoreException.invalid("The amount of " + name + " must be more than zero");
        }
        if (quantity.compareTo(MAX_QUANTITY) > 0) {
            throw CoreException.invalid("The amount of " + name + " is too large");
        }
        BigDecimal scaled = quantity.setScale(SCALE, RoundingMode.HALF_UP);
        if (scaled.signum() == 0) {
            throw CoreException.invalid("The amount of " + name + " is too small to record");
        }
        return scaled;
    }

    private static void requireUnit(Unit unit, String name) {
        if (unit == null) {
            throw CoreException.invalid("A unit is required for " + name + ": COUNT, G or ML");
        }
    }

    private static void requireLines(List<?> lines) {
        if (lines == null || lines.isEmpty()) {
            throw CoreException.invalid("At least one line is required");
        }
    }

    private static List<Object> changed(long householdId, Set<Long> ingredientIds, InventoryTxn txn) {
        List<Object> events = new ArrayList<>();
        ingredientIds.forEach(id -> events.add(new InventoryChanged(householdId, id, txn.getCreatedAt())));
        return events;
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
