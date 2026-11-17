package com.pantrypilot.core.internal.inventory;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTxnIdOrderById(long txnId);

    List<LedgerEntry> findByTxnIdInOrderById(Collection<Long> txnIds);

    /** The ledger's own answer to "how much is in this lot". It must always equal the lot's quantity. */
    @Query("select coalesce(sum(e.delta), 0) from LedgerEntry e where e.lotId = :lotId")
    BigDecimal balanceOfLot(@Param("lotId") long lotId);
}
