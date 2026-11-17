package com.pantrypilot.core.internal.inventory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CookEventRepository extends JpaRepository<CookEvent, Long> {

    List<CookEvent> findByHouseholdIdOrderByIdDesc(long householdId);
}
