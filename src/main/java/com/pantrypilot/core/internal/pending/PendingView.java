package com.pantrypilot.core.internal.pending;

import com.pantrypilot.shared.ProposedChange;
import java.time.Instant;
import java.util.List;

/**
 * A proposal as the review screen shows it.
 *
 * @param change    the proposal in the shared contract's shape, including any edits
 * @param lineNames the catalog name of each line, in order; null for a line that is not matched yet
 * @param txnId     the ledger transaction that applied it, once confirmed
 */
public record PendingView(long id, PendingState state, Instant submittedAt, ProposedChange change,
        List<String> lineNames, Long decidedBy, Instant decidedAt, Long txnId) {
}
