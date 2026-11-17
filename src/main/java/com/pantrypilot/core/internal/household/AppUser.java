package com.pantrypilot.core.internal.household;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A person. Login is not built yet, so a user is only a name and an email. */
@Entity
@Table(name = "core_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String displayName;
    private String email;
    private Instant createdAt;

    protected AppUser() {
    }

    AppUser(String displayName, String email) {
        this.displayName = displayName;
        this.email = email;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }
}
