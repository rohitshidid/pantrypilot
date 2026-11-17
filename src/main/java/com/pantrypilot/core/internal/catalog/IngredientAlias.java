package com.pantrypilot.core.internal.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Another name for an ingredient: a plural, a variety ("roma tomato") or another language ("tamatar"). */
@Entity
@Table(name = "core_ingredient_alias")
public class IngredientAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long ingredientId;
    private String alias;

    protected IngredientAlias() {
    }

    IngredientAlias(long ingredientId, String alias) {
        this.ingredientId = ingredientId;
        this.alias = alias;
    }

    public long getIngredientId() {
        return ingredientId;
    }

    public String getAlias() {
        return alias;
    }
}
