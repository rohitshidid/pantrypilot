package com.pantrypilot.core.internal.inventory;

import com.pantrypilot.core.internal.catalog.StorageLocation;
import com.pantrypilot.shared.Unit;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One purchase of one ingredient, with its own price and expiry date. It is the
 * only row in the module that two members can change at the same moment.
 *
 * <p>{@code @Version} is the optimistic lock. Hibernate writes every change as
 * {@code update core_lot set quantity = ?, version = version + 1 where id = ?
 * and version = ?}. If another member committed first, the version no longer
 * matches, no row is updated, and Hibernate throws instead of silently
 * overwriting their deduction.
 */
@Entity
@Table(name = "core_lot")
public class Lot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long householdId;
    private long ingredientId;
    private BigDecimal quantity;
    @Enumerated(EnumType.STRING)
    private Unit unit;
    private BigDecimal unitPrice;
    @Enumerated(EnumType.STRING)
    private StorageLocation location;
    private LocalDate expiresOn;
    private Long privateOwnerId;
    private Instant createdAt;
    @Version
    private long version;

    protected Lot() {
    }

    Lot(long householdId, long ingredientId, BigDecimal quantity, Unit unit, BigDecimal unitPrice,
            StorageLocation location, LocalDate expiresOn, Long privateOwnerId) {
        this.householdId = householdId;
        this.ingredientId = ingredientId;
        this.quantity = quantity;
        this.unit = unit;
        this.unitPrice = unitPrice;
        this.location = location;
        this.expiresOn = expiresOn;
        this.privateOwnerId = privateOwnerId;
        this.createdAt = Instant.now();
    }

    /** Applies a signed change. The caller has already checked the result is not negative. */
    void change(BigDecimal delta) {
        this.quantity = this.quantity.add(delta);
    }

    public Long getId() {
        return id;
    }

    public long getHouseholdId() {
        return householdId;
    }

    public long getIngredientId() {
        return ingredientId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public Unit getUnit() {
        return unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public StorageLocation getLocation() {
        return location;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public Long getPrivateOwnerId() {
        return privateOwnerId;
    }
}
