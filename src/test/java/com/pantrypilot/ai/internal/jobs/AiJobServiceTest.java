package com.pantrypilot.ai.internal.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pantrypilot.ai.internal.AiProcessingException;
import com.pantrypilot.ai.internal.LineDetail;
import com.pantrypilot.ai.internal.ProposalResult;
import com.pantrypilot.ai.testsupport.FakeAiBackends;
import com.pantrypilot.core.internal.pending.PendingChangeQueue;
import com.pantrypilot.shared.Direction;
import com.pantrypilot.shared.ProposedChange;
import com.pantrypilot.shared.ProposedLine;
import com.pantrypilot.shared.Source;
import com.pantrypilot.shared.Unit;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The threaded job pipeline, with two workers and room for two waiting jobs. */
@SpringBootTest(properties = {
    "pantrypilot.ai.jobs.workers=2",
    "pantrypilot.ai.jobs.queue-capacity=2",
    "pantrypilot.ai.jobs.timeout=2s"
})
@Import(FakeAiBackends.class)
class AiJobServiceTest {

    @Autowired
    private AiJobService jobs;
    @Autowired
    private JobStore store;
    @Autowired
    private PendingChangeQueue pending;

    private static ProposalResult proposal(long householdId, int lines) {
        List<ProposedLine> proposed = new ArrayList<>();
        List<LineDetail> details = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            proposed.add(new ProposedLine("2 tomatoes", 1L, BigDecimal.valueOf(2), Unit.COUNT, null, 0.9));
            details.add(new LineDetail("tomatoes", "tomato", 1.0, null));
        }
        return new ProposalResult(
                new ProposedChange(householdId, 1, Source.PHOTO, Direction.IN, null, null, proposed),
                details, List.of(), Map.of());
    }

    private AiJob awaitFinished(long id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            AiJob job = store.find(id).orElseThrow();
            if (job.getStatus().isFinished()) {
                return job;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("job " + id + " did not finish");
    }

    @Test
    void submitReturnsAtOnceAndTheWorkRunsOnAWorkerThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        String[] threadName = new String[1];

        AiJob job = jobs.submit(JobType.PHOTO, 101, 1, "one photo", () -> {
            threadName[0] = Thread.currentThread().getName();
            await(release);
            return proposal(101, 1);
        });

        // the request thread has its answer while the work is still blocked
        assertThat(job.getId()).isNotNull();
        assertThat(store.find(job.getId()).orElseThrow().getStatus().isFinished()).isFalse();
        release.countDown();

        AiJob finished = awaitFinished(job.getId());
        assertThat(finished.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(finished.getPendingChangeId()).isNotNull();
        assertThat(finished.getResultJson()).contains("\"rawText\":\"2 tomatoes\"");
        assertThat(finished.getStartedAt()).isNotNull();
        assertThat(finished.getFinishedAt()).isNotNull();
        assertThat(threadName[0]).startsWith("ai-job-");
        assertThat(pending.find(finished.getPendingChangeId()).orElseThrow().change().householdId()).isEqualTo(101);
    }

    @Test
    void workersRunInParallelTheQueueIsBoundedAndNoJobIsLost() throws Exception {
        CountDownLatch twoRunning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        Supplier<ProposalResult> work = () -> {
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
            twoRunning.countDown();
            await(release);
            running.decrementAndGet();
            return proposal(102, 1);
        };

        List<AiJob> accepted = new ArrayList<>();
        accepted.add(jobs.submit(JobType.PHOTO, 102, 1, "a", work));
        accepted.add(jobs.submit(JobType.PHOTO, 102, 1, "b", work));
        assertThat(twoRunning.await(5, TimeUnit.SECONDS)).as("both workers busy at the same time").isTrue();
        accepted.add(jobs.submit(JobType.PHOTO, 102, 1, "c", work));
        accepted.add(jobs.submit(JobType.PHOTO, 102, 1, "d", work));

        // two running and two waiting: a fifth is refused, and recorded as failed rather than lost
        assertThatThrownBy(() -> jobs.submit(JobType.PHOTO, 102, 1, "e", work))
                .isInstanceOf(QueueFullException.class);
        release.countDown();

        List<Long> pendingIds = new ArrayList<>();
        for (AiJob job : accepted) {
            AiJob finished = awaitFinished(job.getId());
            assertThat(finished.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
            pendingIds.add(finished.getPendingChangeId());
        }
        assertThat(pendingIds).doesNotHaveDuplicates().hasSize(4);
        assertThat(mostAtOnce.get()).isEqualTo(2);
        assertThat(pending.forHousehold(102)).hasSize(4);

        List<AiJob> all = store.recent(102);
        assertThat(all).hasSize(5);
        assertThat(all).filteredOn(j -> j.getStatus() == JobStatus.FAILED)
                .singleElement()
                .satisfies(j -> assertThat(j.getError()).contains("busy"));
    }

    @Test
    void failuresAreRecordedWithAMessageTheUserCanRead() throws Exception {
        AiJob known = jobs.submit(JobType.RECEIPT, 103, 1, "r.png", () -> {
            throw new AiProcessingException("No text could be read from that photo");
        });
        AiJob unknown = jobs.submit(JobType.RECEIPT, 103, 1, "r.png", () -> {
            throw new IllegalStateException("NullPointer at SecretInternals.java:42");
        });
        AiJob empty = jobs.submit(JobType.PHOTO, 103, 1, "p.png", () -> proposal(103, 0));

        assertThat(awaitFinished(known.getId()).getError()).isEqualTo("No text could be read from that photo");
        AiJob unknownFinished = awaitFinished(unknown.getId());
        assertThat(unknownFinished.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(unknownFinished.getError()).doesNotContain("SecretInternals").contains("went wrong");
        AiJob emptyFinished = awaitFinished(empty.getId());
        assertThat(emptyFinished.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(emptyFinished.getError()).isEqualTo(JobStore.NOTHING_FOUND);
        assertThat(pending.forHousehold(103)).isEmpty();
    }

    @Test
    void aJobThatRunsTooLongFailsAndItsLateResultIsNeverSubmitted() throws Exception {
        CountDownLatch workDone = new CountDownLatch(1);
        AiJob job = jobs.submit(JobType.LINK, 104, 1, "https://slow.example", () -> {
            sleep(3000);
            workDone.countDown();
            return proposal(104, 1);
        });

        AiJob finished = awaitFinished(job.getId());
        assertThat(finished.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(finished.getError()).contains("too long");

        assertThat(workDone.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);
        assertThat(store.find(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(pending.forHousehold(104)).isEmpty();
    }

    @Test
    void unfinishedJobsFromAPreviousRunAreFailedOnStartup() {
        AiJob orphan = store.create(JobType.PHOTO, 105, 1, "left over");

        assertThat(store.failInterrupted()).isGreaterThanOrEqualTo(1);

        AiJob after = store.find(orphan.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getError()).contains("restarted");
        assertThat(store.markRunning(orphan.getId())).isFalse();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
