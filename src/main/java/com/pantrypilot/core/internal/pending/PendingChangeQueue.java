package com.pantrypilot.core.internal.pending;

import com.pantrypilot.core.internal.ConflictRetry;
import com.pantrypilot.core.internal.household.HouseholdService;
import com.pantrypilot.core.internal.inventory.InventoryWriter.Shortfall;
import com.pantrypilot.core.internal.pending.PendingStore.Confirmed;
import com.pantrypilot.shared.PendingChangeService;
import com.pantrypilot.shared.ProposedChange;
import com.pantrypilot.shared.ProposedLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The confirm-before-apply queue. The AI module hands proposals to
 * {@link #submit}; nothing reaches the inventory until a member of that
 * household calls {@link #confirm}.
 *
 * <p>Like {@code InventoryService} this class is not transactional itself. It
 * runs the {@link PendingStore} transaction (again if it lost a race) and
 * publishes the events only after the commit.
 */
@Service
public class PendingChangeQueue implements PendingChangeService {

    /** @param cost what the used ingredients cost at receipt prices; zero for stock-in */
    public record ConfirmResult(PendingView pending, long txnId, BigDecimal cost, List<Shortfall> shortfalls) {
    }

    private final PendingStore store;
    private final HouseholdService households;
    private final ConflictRetry retry;
    private final ApplicationEventPublisher publisher;

    public PendingChangeQueue(PendingStore store, HouseholdService households, ConflictRetry retry,
            ApplicationEventPublisher publisher) {
        this.store = store;
        this.households = households;
        this.retry = retry;
        this.publisher = publisher;
    }

    @Override
    public long submit(ProposedChange change) {
        return store.submit(change);
    }

    /**
     * @param strict true: if any ingredient is short, nothing is applied.
     *               false: use what is there and report what was missing.
     */
    public ConfirmResult confirm(long id, long userId, boolean strict) {
        Confirmed confirmed = retry.run(() -> store.confirm(id, userId, strict));
        confirmed.applied().events().forEach(publisher::publishEvent);
        return new ConfirmResult(confirmed.view(), confirmed.applied().txnId(), confirmed.applied().cost(),
                confirmed.applied().shortfalls());
    }

    public PendingView reject(long id, long userId) {
        return retry.run(() -> store.reject(id, userId));
    }

    public PendingView edit(long id, long userId, List<ProposedLine> lines) {
        return retry.run(() -> store.edit(id, userId, lines));
    }

    /** The household's proposals, newest first, for a member of that household. */
    public List<PendingView> listFor(long householdId, long userId) {
        households.requireMember(householdId, userId);
        return store.forHousehold(householdId);
    }

    public Optional<PendingView> findFor(long id, long userId) {
        households.requireMember(store.householdOf(id), userId);
        return store.find(id);
    }

    /** Unchecked lookups, for use inside the server (and tests), not for the REST layer. */
    public Optional<PendingView> find(long id) {
        return store.find(id);
    }

    public List<PendingView> forHousehold(long householdId) {
        return store.forHousehold(householdId);
    }
}
