package com.pantrypilot.core.internal.pending;

import com.pantrypilot.shared.Direction;
import com.pantrypilot.shared.Source;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * An AI proposal waiting for a member to confirm, edit or reject it.
 *
 * <p>It has a {@code @Version} too. If two members press Confirm on the same
 * proposal at the same moment, both transactions apply the lines, but only one
 * can move the state from PENDING to CONFIRMED; the other is rolled back
 * completely, so the groceries are never added twice.
 */
@Entity
@Table(name = "core_pending_change")
public class PendingChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long householdId;
    private long createdBy;
    @Enumerated(EnumType.STRING)
    private Source source;
    @Enumerated(EnumType.STRING)
    private Direction direction;
    private String dishName;
    private Integer servings;
    @Enumerated(EnumType.STRING)
    private PendingState state;
    private Instant submittedAt;
    private Instant editedAt;
    private Instant decidedAt;
    private Long decidedBy;
    private Long txnId;
    @Version
    private long version;

    protected PendingChange() {
    }

    PendingChange(long householdId, long createdBy, Source source, Direction direction, String dishName,
            Integer servings) {
        this.householdId = householdId;
        this.createdBy = createdBy;
        this.source = source;
        this.direction = direction;
        this.dishName = dishName;
        this.servings = servings;
        this.state = PendingState.PENDING;
        this.submittedAt = Instant.now();
    }

    void decide(PendingState decision, long userId, Long txnId) {
        this.state = decision;
        this.decidedBy = userId;
        this.decidedAt = Instant.now();
        this.txnId = txnId;
    }

    /**
     * Records that a member corrected the lines. Changing a column also bumps the
     * version, so an edit and a confirm made at the same moment cannot both succeed.
     */
    void markEdited() {
        this.editedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public long getHouseholdId() {
        return householdId;
    }

    public long getCreatedBy() {
        return createdBy;
    }

    public Source getSource() {
        return source;
    }

    public Direction getDirection() {
        return direction;
    }

    public String getDishName() {
        return dishName;
    }

    public Integer getServings() {
        return servings;
    }

    public PendingState getState() {
        return state;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Long getDecidedBy() {
        return decidedBy;
    }

    public Long getTxnId() {
        return txnId;
    }
}
