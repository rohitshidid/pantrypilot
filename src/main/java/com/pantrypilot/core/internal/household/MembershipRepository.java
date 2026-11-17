package com.pantrypilot.core.internal.household;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

    Optional<Membership> findByHouseholdIdAndUserId(long householdId, long userId);

    List<Membership> findByHouseholdIdOrderById(long householdId);

    List<Membership> findByUserIdOrderById(long userId);

    long countByHouseholdIdAndRole(long householdId, Role role);
}
