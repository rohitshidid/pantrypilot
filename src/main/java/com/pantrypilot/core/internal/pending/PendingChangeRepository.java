package com.pantrypilot.core.internal.pending;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingChangeRepository extends JpaRepository<PendingChange, Long> {

    List<PendingChange> findByHouseholdIdOrderByIdDesc(long householdId);
}
