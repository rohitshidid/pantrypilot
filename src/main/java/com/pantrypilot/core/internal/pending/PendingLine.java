package com.pantrypilot.core.internal.pending;

import com.pantrypilot.shared.ProposedLine;
import com.pantrypilot.shared.Unit;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** One line of a waiting proposal, stored exactly as proposed until the user edits it. */
@Entity
@Table(name = "core_pending_line")
public class PendingLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long pendingChangeId;
    private int lineNo;
    private String rawText;
    private Long ingredientId;
    private BigDecimal quantity;
    @Enumerated(EnumType.STRING)
    private Unit unit;
    private BigDecimal unitPrice;
    private double confidence;

    protected PendingLine() {
    }

    PendingLine(long pendingChangeId, int lineNo, ProposedLine line) {
        this.pendingChangeId = pendingChangeId;
        this.lineNo = lineNo;
        String raw = line.rawText();
        this.rawText = raw != null && raw.length() > 500 ? raw.substring(0, 500) : raw;
        this.ingredientId = line.ingredientId();
        this.quantity = line.quantity();
        this.unit = line.unit();
        this.unitPrice = line.unitPrice();
        this.confidence = line.confidence();
    }

    ProposedLine toProposedLine() {
        return new ProposedLine(rawText, ingredientId, quantity, unit, unitPrice, confidence);
    }

    public long getPendingChangeId() {
        return pendingChangeId;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getRawText() {
        return rawText;
    }

    public Long getIngredientId() {
        return ingredientId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public Unit getUnit() {
        return unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }
}
