package com.pantrypilot.core.internal.pending;

import com.pantrypilot.core.internal.CoreException;
import com.pantrypilot.core.internal.catalog.Ingredient;
import com.pantrypilot.core.internal.catalog.IngredientRepository;
import com.pantrypilot.core.internal.household.HouseholdService;
import com.pantrypilot.core.internal.inventory.EntryType;
import com.pantrypilot.core.internal.inventory.InventoryWriter;
import com.pantrypilot.core.internal.inventory.InventoryWriter.Applied;
import com.pantrypilot.core.internal.inventory.InventoryWriter.InLine;
import com.pantrypilot.core.internal.inventory.InventoryWriter.OutLine;
import com.pantrypilot.core.internal.inventory.TxnKind;
import com.pantrypilot.shared.Direction;
import com.pantrypilot.shared.ProposedChange;
import com.pantrypilot.shared.ProposedLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of the confirmation queue. {@link #confirm} is the
 * method the whole project's atomicity claim rests on.
 */
@Service
@Transactional
public class PendingStore {

    public record Confirmed(PendingView view, Applied applied) {
    }

    private final PendingChangeRepository changes;
    private final PendingLineRepository lines;
    private final IngredientRepository ingredients;
    private final HouseholdService households;
    private final InventoryWriter writer;

    public PendingStore(PendingChangeRepository changes, PendingLineRepository lines, IngredientRepository ingredients,
            HouseholdService households, InventoryWriter writer) {
        this.changes = changes;
        this.lines = lines;
        this.ingredients = ingredients;
        this.households = households;
        this.writer = writer;
    }

    /** Stores a proposal. Nothing in the inventory changes. */
    public long submit(ProposedChange change) {
        if (change == null || change.source() == null || change.direction() == null) {
            throw new IllegalArgumentException("A proposal needs a source and a direction");
        }
        PendingChange saved = changes.save(new PendingChange(change.householdId(), change.createdBy(),
                change.source(), change.direction(), truncate(change.dishName()), change.servings()));
        saveLines(saved.getId(), change.lines());
        return saved.getId();
    }

    /**
     * Applies a proposal. Everything below happens in ONE transaction:
     * the lots, the ledger rows, the cook event and the CONFIRMED state.
     * If any line throws (unmatched, not enough stock, bad amount), Spring rolls
     * all of it back and the proposal is still PENDING, exactly as before.
     */
    public Confirmed confirm(long id, long userId, boolean strict) {
        PendingChange change = require(id, userId);
        requirePending(change);
        List<PendingLine> stored = lines.findByPendingChangeIdOrderByLineNo(id);
        if (stored.isEmpty()) {
            throw CoreException.invalid("This proposal has no lines left. Reject it instead.");
        }
        for (PendingLine line : stored) {
            if (line.getIngredientId() == null) {
                throw CoreException.invalid("\"" + line.getRawText() + "\" is not matched to an ingredient yet. "
                        + "Pick one or remove the line, then confirm again.");
            }
        }
        long householdId = change.getHouseholdId();
        Applied applied;
        if (change.getDirection() == Direction.IN) {
            List<InLine> in = stored.stream()
                    .map(l -> new InLine(l.getIngredientId(), l.getQuantity(), l.getUnit(), l.getUnitPrice(),
                            null, null, false))
                    .toList();
            applied = writer.stockIn(householdId, userId, TxnKind.CONFIRM_IN, id,
                    "Confirmed " + change.getSource() + " proposal #" + id, in);
        } else {
            List<OutLine> out = stored.stream()
                    .map(l -> new OutLine(l.getIngredientId(), l.getQuantity(), l.getUnit()))
                    .toList();
            applied = writer.takeOut(householdId, userId, TxnKind.CONFIRM_OUT, EntryType.CONSUME, id,
                    change.getDishName(), change.getServings(),
                    "Confirmed " + change.getSource() + " proposal #" + id, out, strict);
        }
        change.decide(PendingState.CONFIRMED, userId, applied.txnId());
        // Written now, not at commit, so a lost race on the proposal surfaces here.
        changes.saveAndFlush(change);
        return new Confirmed(view(change, stored), applied);
    }

    public PendingView reject(long id, long userId) {
        PendingChange change = require(id, userId);
        requirePending(change);
        change.decide(PendingState.REJECTED, userId, null);
        changes.saveAndFlush(change);
        return view(change, lines.findByPendingChangeIdOrderByLineNo(id));
    }

    /** Replaces the lines with the user's corrected ones. Only possible while the proposal is waiting. */
    public PendingView edit(long id, long userId, List<ProposedLine> corrected) {
        PendingChange change = require(id, userId);
        requirePending(change);
        if (corrected == null || corrected.isEmpty()) {
            throw CoreException.invalid("At least one line is required. To drop the proposal, reject it.");
        }
        for (ProposedLine line : corrected) {
            if (line.quantity() == null || line.quantity().signum() <= 0 || line.unit() == null) {
                throw CoreException.invalid("Every line needs an amount above zero and a unit");
            }
        }
        lines.deleteByPendingChangeId(id);
        lines.flush();
        saveLines(id, corrected);
        change.markEdited();
        changes.saveAndFlush(change);
        return view(change, lines.findByPendingChangeIdOrderByLineNo(id));
    }

    @Transactional(readOnly = true)
    public Optional<PendingView> find(long id) {
        return changes.findById(id).map(c -> view(c, lines.findByPendingChangeIdOrderByLineNo(id)));
    }

    @Transactional(readOnly = true)
    public List<PendingView> forHousehold(long householdId) {
        List<PendingChange> found = changes.findByHouseholdIdOrderByIdDesc(householdId);
        Map<Long, List<PendingLine>> byChange = lines
                .findByPendingChangeIdInOrderByLineNo(found.stream().map(PendingChange::getId).toList()).stream()
                .collect(Collectors.groupingBy(PendingLine::getPendingChangeId));
        return found.stream().map(c -> view(c, byChange.getOrDefault(c.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public long householdOf(long id) {
        return changes.findById(id).map(PendingChange::getHouseholdId)
                .orElseThrow(() -> CoreException.notFound("No such proposal"));
    }

    private PendingChange require(long id, long userId) {
        PendingChange change = changes.findById(id).orElseThrow(() -> CoreException.notFound("No such proposal"));
        households.requireMember(change.getHouseholdId(), userId);
        return change;
    }

    private static void requirePending(PendingChange change) {
        if (change.getState() != PendingState.PENDING) {
            throw CoreException.conflict("This proposal was already "
                    + change.getState().name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private void saveLines(long pendingChangeId, List<ProposedLine> proposed) {
        int lineNo = 0;
        for (ProposedLine line : proposed) {
            lines.save(new PendingLine(pendingChangeId, lineNo++, line));
        }
    }

    private PendingView view(PendingChange change, List<PendingLine> stored) {
        Map<Long, String> names = ingredients.findAllById(
                        stored.stream().map(PendingLine::getIngredientId).filter(i -> i != null).distinct().toList())
                .stream().collect(Collectors.toMap(Ingredient::getId, Ingredient::getName));
        List<String> lineNames = new ArrayList<>();
        stored.forEach(l -> lineNames.add(l.getIngredientId() == null ? null : names.get(l.getIngredientId())));
        ProposedChange shape = new ProposedChange(change.getHouseholdId(), change.getCreatedBy(), change.getSource(),
                change.getDirection(), change.getDishName(), change.getServings(),
                stored.stream().map(PendingLine::toProposedLine).toList());
        return new PendingView(change.getId(), change.getState(), change.getSubmittedAt(), shape,
                java.util.Collections.unmodifiableList(lineNames), change.getDecidedBy(), change.getDecidedAt(),
                change.getTxnId());
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) : s;
    }
}
