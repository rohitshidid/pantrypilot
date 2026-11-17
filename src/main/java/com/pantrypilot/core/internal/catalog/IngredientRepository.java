package com.pantrypilot.core.internal.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngredientRepository extends JpaRepository<Ingredient, Long> {

    List<Ingredient> findByHouseholdIdOrderById(long householdId);

    Optional<Ingredient> findByHouseholdIdAndNameIgnoreCase(long householdId, String name);
}
