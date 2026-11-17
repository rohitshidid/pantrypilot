package com.pantrypilot.core.internal.inventory;

import com.pantrypilot.shared.Unit;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the append-only ledger: this much of this lot moved, signed
 * (+ in, - out), by this user, as part of this transaction. Rows are only ever
 * inserted, so the ledger is the audit log.
 */
@Entity
@Table(name = "core_ledger_entry")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long txnId;
    private long householdId;
    private long lotId;
    private long ingredientId;
    private long userId;
    @Enumerated(EnumType.STRING)
    private EntryType type;
    private BigDecimal delta;
    @Enumerated(EnumType.STRING)
    private Unit unit;
    private BigDecimal unitPrice;
    private Instant createdAt;

    protected LedgerEntry() {
    }

    LedgerEntry(InventoryTxn txn, Lot lot, EntryType type, BigDecimal delta) {
        this.txnId = txn.getId();
        this.householdId = txn.getHouseholdId();
        this.userId = txn.getUserId();
        this.lotId = lot.getId();
        this.ingredientId = lot.getIngredientId();
        this.type = type;
        this.delta = delta;
        this.unit = lot.getUnit();
        this.unitPrice = lot.getUnitPrice();
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public long getTxnId() {
        return txnId;
    }

    public long getLotId() {
        return lotId;
    }

    public long getIngredientId() {
        return ingredientId;
    }

    public EntryType getType() {
        return type;
    }

    public BigDecimal getDelta() {
        return delta;
    }

    public Unit getUnit() {
        return unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }
}
