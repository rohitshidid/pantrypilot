package com.pantrypilot.core.internal.inventory;

/** Why a change was applied. */
public enum TxnKind {
    MANUAL_IN, MANUAL_OUT, CONFIRM_IN, CONFIRM_OUT, DISCARD, LEFTOVER, UNDO
}
