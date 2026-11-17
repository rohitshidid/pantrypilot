package com.pantrypilot.core.internal.household;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Links one user to one household with a role. A user may have many of these. */
@Entity
@Table(name = "core_membership")
public class Membership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long householdId;
    private long userId;
    @Enumerated(EnumType.STRING)
    private Role role;
    private Instant joinedAt;

    protected Membership() {
    }

    Membership(long householdId, long userId, Role role) {
        this.householdId = householdId;
        this.userId = userId;
        this.role = role;
        this.joinedAt = Instant.now();
    }

    public long getHouseholdId() {
        return householdId;
    }

    public long getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    void setRole(Role role) {
        this.role = role;
    }
}
