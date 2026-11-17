package com.pantrypilot.core.internal.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pantrypilot.core.internal.CoreException;
import com.pantrypilot.core.internal.household.HouseholdService;
import com.pantrypilot.core.internal.inventory.InventoryService.ChangeResult;
import com.pantrypilot.core.internal.inventory.InventoryWriter.InLine;
import com.pantrypilot.core.internal.inventory.InventoryWriter.OutLine;
import com.pantrypilot.core.internal.pending.PendingChangeQueue;
import com.pantrypilot.core.testsupport.Kitchen;
import com.pantrypilot.shared.CatalogService;
import com.pantrypilot.shared.Direction;
import com.pantrypilot.shared.ProposedChange;
import com.pantrypilot.shared.ProposedLine;
import com.pantrypilot.shared.Source;
import com.pantrypilot.shared.Unit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The proposal's central claim: many household members changing the same
 * inventory at the same moment never lose an update, never oversell, and never
 * apply one thing twice.
 */
@SpringBootTest
class ConcurrencyTest {

    private static final int MEMBERS = 16;

    @Autowired
    private InventoryService inventory;
    @Autowired
    private PendingChangeQueue queue;
    @Autowired
    private HouseholdService households;
    @Autowired
    private CatalogService catalog;
    @Autowired
    private LotRepository lots;
    @Autowired
    private LedgerEntryRepository ledger;
    @Autowired
    private TransactionTemplate transaction;

    private long h;
    private long admin;
    private long tomato;

    @BeforeEach
    void freshKitchen() {
        Kitchen kitchen = Kitchen.create(households);
        h = kitchen.householdId();
        admin = kitchen.adminId();
        tomato = catalog.resolve("tomato").orElseThrow().ingredientId();
    }

    private long stockTomatoes(String count) {
        ChangeResult added = inventory.stockIn(h, admin,
                List.of(new InLine(tomato, new BigDecimal(count), Unit.COUNT, null, null, null, false)));
        return inventory.history(h, admin, 200).stream().filter(t -> t.id() == added.txnId())
                .findFirst().orElseThrow().entries().get(0).lotId();
    }

    private ChangeResult takeTomatoes(String count) {
        return inventory.consume(h, admin, List.of(new OutLine(tomato, new BigDecimal(count), Unit.COUNT)),
                true, null, null);
    }

    /**
     * Runs the task on MEMBERS threads that are all released at the same instant.
     *
     * @return one entry per thread: its result, or the exception it ended with
     */
    private static <T> List<Object> allAtOnce(Callable<T> task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(MEMBERS);
        CountDownLatch ready = new CountDownLatch(MEMBERS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < MEMBERS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    outcomes.add(future.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new AssertionError("a thread did not finish", e);
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long succeeded(List<Object> outcomes) {
        return outcomes.stream().filter(o -> !(o instanceof Throwable)).count();
    }

    private static void assertOthersWereRefusedCleanly(List<Object> outcomes) {
        assertThat(outcomes).filteredOn(o -> o instanceof Throwable).allSatisfy(o ->
                assertThat(o).isInstanceOfSatisfying(CoreException.class,
                        e -> assertThat(e.kind()).isEqualTo(CoreException.Kind.CONFLICT)));
    }

    // ---- the mechanism ----------------------------------------------------------

    /** What @Version does, step by step, with nothing retrying: the second writer is refused. */
    @Test
    void aStaleWriteIsRefusedInsteadOfOverwritingTheOtherMembersChange() throws Exception {
        long lotId = stockTomatoes("10");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            Lot mine = lots.findById(lotId).orElseThrow();   // I read 10
            runOnAnotherThread(() -> takeTomatoes("4"));     // meanwhile someone else takes 4 and commits
            mine.change(new BigDecimal("-1"));               // I still think there are 10 and write 9
            lots.flush();
        })).isInstanceOf(OptimisticLockingFailureException.class);

        // Without the version check the result would be 9 and the other member's 4 would be lost.
        assertThat(lots.findById(lotId).orElseThrow().getQuantity()).isEqualByComparingTo("6");
    }

    private static void runOnAnotherThread(Runnable work) {
        Thread thread = new Thread(work);
        thread.start();
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---- the guarantees ---------------------------------------------------------

    @Test
    void simultaneousDeductionsAreAllAppliedAndNoneIsLost() throws Exception {
        long lotId = stockTomatoes("100");

        List<Object> outcomes = allAtOnce(() -> takeTomatoes("2"));

        assertThat(outcomes).as("every member's deduction went through").noneMatch(o -> o instanceof Throwable);
        // 100 - 16 x 2. A lost update would leave more than 68.
        assertThat(lots.findById(lotId).orElseThrow().getQuantity()).isEqualByComparingTo("68");
        assertThat(ledger.balanceOfLot(lotId)).isEqualByComparingTo("68");
        assertThat(inventory.history(h, admin, 200)).hasSize(MEMBERS + 1);
    }

    @Test
    void stockIsNeverOversoldWhenMoreIsWantedThanExists() throws Exception {
        long lotId = stockTomatoes("10");

        List<Object> outcomes = allAtOnce(() -> takeTomatoes("1"));   // 16 people want 1 each; there are 10

        assertThat(succeeded(outcomes)).isEqualTo(10);
        assertOthersWereRefusedCleanly(outcomes);
        assertThat(lots.findById(lotId).orElseThrow().getQuantity()).isEqualByComparingTo("0");
        assertThat(ledger.balanceOfLot(lotId)).isEqualByComparingTo("0");
    }

    @Test
    void aProposalConfirmedByEveryoneAtOnceIsAppliedExactlyOnce() throws Exception {
        long pendingId = queue.submit(new ProposedChange(h, admin, Source.RECEIPT, Direction.IN, null, null, List.of(
                new ProposedLine("TOMATOES 6CT", tomato, new BigDecimal("6"), Unit.COUNT, new BigDecimal("0.5"), 0.9))));

        List<Object> outcomes = allAtOnce(() -> queue.confirm(pendingId, admin, true));

        assertThat(succeeded(outcomes)).isEqualTo(1);
        assertOthersWereRefusedCleanly(outcomes);
        // six tomatoes, not ninety-six
        assertThat(inventory.pantry(h, admin)).singleElement()
                .satisfies(lot -> assertThat(lot.quantity()).isEqualByComparingTo("6"));
        assertThat(inventory.history(h, admin, 200)).hasSize(1);
    }

    @Test
    void aChangeUndoneByEveryoneAtOnceIsUndoneExactlyOnce() throws Exception {
        long lotId = stockTomatoes("10");
        ChangeResult used = takeTomatoes("3");

        List<Object> outcomes = allAtOnce(() -> inventory.undo(h, admin, used.txnId()));

        assertThat(succeeded(outcomes)).isEqualTo(1);
        assertOthersWereRefusedCleanly(outcomes);
        assertThat(lots.findById(lotId).orElseThrow().getQuantity()).isEqualByComparingTo("10");   // not 13 or more
        assertThat(ledger.balanceOfLot(lotId)).isEqualByComparingTo("10");
    }

    @Test
    void confirmingAndRejectingAtTheSameTimeLeavesOneClearDecision() throws Exception {
        long pendingId = queue.submit(new ProposedChange(h, admin, Source.PHOTO, Direction.IN, null, null, List.of(
                new ProposedLine("tomatoes", tomato, new BigDecimal("4"), Unit.COUNT, null, 0.8))));
        java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();

        List<Object> outcomes = allAtOnce(() -> turn.getAndIncrement() % 2 == 0
                ? queue.confirm(pendingId, admin, true)
                : queue.reject(pendingId, admin));

        assertThat(succeeded(outcomes)).isEqualTo(1);
        assertOthersWereRefusedCleanly(outcomes);
        boolean confirmed = queue.find(pendingId).orElseThrow().txnId() != null;
        assertThat(inventory.pantry(h, admin)).hasSize(confirmed ? 1 : 0);
    }
}
