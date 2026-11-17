package com.pantrypilot.core.internal.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One canonical ingredient. {@code gramsEach} and {@code gramsPerMl} are what
 * let an amount be read as count, grams and millilitres at once.
 */
@Entity
@Table(name = "core_ingredient")
public class Ingredient {

    /** {@code householdId} of rows that belong to the shared catalog. */
    public static final long SHARED = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long householdId;
    private String name;
    @Enumerated(EnumType.STRING)
    private IngredientKind kind;
    private BigDecimal gramsEach;
    private BigDecimal gramsPerMl;
    @Enumerated(EnumType.STRING)
    private StorageLocation defaultLocation;
    private Integer shelfDaysPantry;
    private Integer shelfDaysFridge;
    private Integer shelfDaysFreezer;

    protected Ingredient() {
    }

    public Ingredient(long householdId, String name, IngredientKind kind, BigDecimal gramsEach, BigDecimal gramsPerMl,
            StorageLocation defaultLocation, Integer shelfDaysPantry, Integer shelfDaysFridge, Integer shelfDaysFreezer) {
        this.householdId = householdId;
        this.name = name;
        this.kind = kind;
        this.gramsEach = gramsEach;
        this.gramsPerMl = gramsPerMl;
        this.defaultLocation = defaultLocation;
        this.shelfDaysPantry = shelfDaysPantry;
        this.shelfDaysFridge = shelfDaysFridge;
        this.shelfDaysFreezer = shelfDaysFreezer;
    }

    /** @return days it keeps in that place, or null when unknown */
    public Integer shelfDays(StorageLocation location) {
        return switch (location) {
            case PANTRY -> shelfDaysPantry;
            case FRIDGE -> shelfDaysFridge;
            case FREEZER -> shelfDaysFreezer;
        };
    }

    public Long getId() {
        return id;
    }

    public long getHouseholdId() {
        return householdId;
    }

    public String getName() {
        return name;
    }

    public IngredientKind getKind() {
        return kind;
    }

    public BigDecimal getGramsEach() {
        return gramsEach;
    }

    public BigDecimal getGramsPerMl() {
        return gramsPerMl;
    }

    public StorageLocation getDefaultLocation() {
        return defaultLocation;
    }
}
