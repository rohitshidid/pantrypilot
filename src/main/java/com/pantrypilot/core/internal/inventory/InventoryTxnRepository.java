package com.pantrypilot.core.internal.inventory;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryTxnRepository extends JpaRepository<InventoryTxn, Long> {

    boolean existsByReversesTxnId(long reversesTxnId);

    List<InventoryTxn> findByHouseholdIdOrderByIdDesc(long householdId, Pageable page);

    List<InventoryTxn> findByReversesTxnIdIn(Collection<Long> txnIds);
}
