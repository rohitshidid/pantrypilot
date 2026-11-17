package com.pantrypilot.core.internal.inventory;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LotRepository extends JpaRepository<Lot, Long> {

    /**
     * What one user may see and use: shared lots plus their own private ones,
     * soonest expiry first (lots with no date last).
     */
    @Query("""
            select l from Lot l
            where l.householdId = :householdId and l.quantity > 0
              and (l.privateOwnerId is null or l.privateOwnerId = :userId)
            order by l.expiresOn asc nulls last, l.id asc""")
    List<Lot> visible(@Param("householdId") long householdId, @Param("userId") long userId);

    @Query("""
            select l from Lot l
            where l.householdId = :householdId and l.ingredientId = :ingredientId and l.quantity > 0
              and (l.privateOwnerId is null or l.privateOwnerId = :userId)
            order by l.expiresOn asc nulls last, l.id asc""")
    List<Lot> visibleOf(@Param("householdId") long householdId, @Param("userId") long userId,
            @Param("ingredientId") long ingredientId);

    @Query("select l from Lot l where l.quantity > 0 and l.expiresOn in :dates order by l.id")
    List<Lot> expiringOn(@Param("dates") Collection<LocalDate> dates);
}
