package com.pantrypilot.core.internal.inventory;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** A dish that was cooked, with what its ingredients really cost at receipt prices. */
@Entity
@Table(name = "core_cook_event")
public class CookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long householdId;
    private long userId;
    private String dishName;
    private int servings;
    private BigDecimal ingredientCost;
    private long txnId;
    private Instant cookedAt;

    protected CookEvent() {
    }

    CookEvent(InventoryTxn txn, String dishName, int servings, BigDecimal ingredientCost) {
        this.householdId = txn.getHouseholdId();
        this.userId = txn.getUserId();
        this.txnId = txn.getId();
        this.dishName = dishName;
        this.servings = servings;
        this.ingredientCost = ingredientCost;
        this.cookedAt = txn.getCreatedAt();
    }

    public String getDishName() {
        return dishName;
    }

    public int getServings() {
        return servings;
    }

    public BigDecimal getIngredientCost() {
        return ingredientCost;
    }

    public long getTxnId() {
        return txnId;
    }
}
