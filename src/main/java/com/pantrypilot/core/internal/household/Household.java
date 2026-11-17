package com.pantrypilot.core.internal.household;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One shared kitchen. People join it with the invite code. */
@Entity
@Table(name = "core_household")
public class Household {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String inviteCode;
    private Instant createdAt;

    protected Household() {
    }

    Household(String name, String inviteCode) {
        this.name = name;
        this.inviteCode = inviteCode;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getInviteCode() {
        return inviteCode;
    }

    void setInviteCode(String inviteCode) {
        this.inviteCode = inviteCode;
    }
}
