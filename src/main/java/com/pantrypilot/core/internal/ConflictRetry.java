package com.pantrypilot.core.internal;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

/**
 * Runs a transaction again when it lost a race with another member.
 *
 * <p>Optimistic locking does not wait for other users: two members may read the
 * same lot, and whoever commits second is refused because the row's version
 * moved. That transaction is rolled back completely, so it is safe to run it
 * again from the start, where it reads the new quantity. The work passed in
 * must therefore be one whole transaction, never part of one.
 */
@Component
public class ConflictRetry {

    static final int MAX_ATTEMPTS = 40;

    public <T> T run(Supplier<T> transaction) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (ConcurrencyFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                pause(attempt);
            }
        }
    }

    /** A short random wait, so the members that collided do not collide again in step. */
    private static void pause(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(2, 6L + 4L * Math.min(attempt, 10)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying", e);
        }
    }
}
