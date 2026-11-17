package com.pantrypilot.core.internal.inventory;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One applied change: a confirmed proposal, a manual entry or an undo. It groups
 * the ledger rows that were written together. It has no setters: once written
 * it is history.
 */
@Entity
@Table(name = "core_txn")
public class InventoryTxn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long householdId;
    private long userId;
    @Enumerated(EnumType.STRING)
    private TxnKind kind;
    private Long pendingChangeId;
    private String dishName;
    private Integer servings;
    private String note;
    private Long reversesTxnId;
    private Instant createdAt;

    protected InventoryTxn() {
    }

    InventoryTxn(long householdId, long userId, TxnKind kind, Long pendingChangeId, String dishName,
            Integer servings, String note, Long reversesTxnId) {
        this.householdId = householdId;
        this.userId = userId;
        this.kind = kind;
        this.pendingChangeId = pendingChangeId;
        this.dishName = dishName;
        this.servings = servings;
        this.note = note;
        this.reversesTxnId = reversesTxnId;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public long getHouseholdId() {
        return householdId;
    }

    public long getUserId() {
        return userId;
    }

    public TxnKind getKind() {
        return kind;
    }

    public Long getPendingChangeId() {
        return pendingChangeId;
    }

    public String getDishName() {
        return dishName;
    }

    public Integer getServings() {
        return servings;
    }

    public String getNote() {
        return note;
    }

    public Long getReversesTxnId() {
        return reversesTxnId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
