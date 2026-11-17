package com.pantrypilot.core.internal.pending;

/** A proposal waits as PENDING until a member decides it. A decision is final. */
public enum PendingState {
    PENDING, CONFIRMED, REJECTED
}
