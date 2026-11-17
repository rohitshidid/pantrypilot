package com.pantrypilot.core.internal.web;

import com.pantrypilot.core.internal.CoreException;
import com.pantrypilot.core.internal.pending.PendingChangeQueue;
import com.pantrypilot.core.internal.pending.PendingChangeQueue.ConfirmResult;
import com.pantrypilot.core.internal.pending.PendingView;
import com.pantrypilot.shared.ProposedLine;
import com.pantrypilot.shared.Unit;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The review screen's endpoints: see what the AI proposed, correct it, then confirm or reject. */
@RestController
@RequestMapping("/api/core/pending")
public class PendingController {

    record LineBody(String rawText, Long ingredientId, String name, BigDecimal quantity, Unit unit,
            BigDecimal unitPrice) {
    }

    record EditBody(List<LineBody> lines) {
    }

    private final PendingChangeQueue queue;
    private final LineResolver resolver;

    public PendingController(PendingChangeQueue queue, LineResolver resolver) {
        this.queue = queue;
        this.resolver = resolver;
    }

    @GetMapping
    List<PendingView> list(@RequestParam long householdId, @RequestParam long userId) {
        return queue.listFor(householdId, userId);
    }

    @GetMapping("/{id}")
    PendingView one(@PathVariable long id, @RequestParam long userId) {
        return queue.findFor(id, userId).orElseThrow(() -> CoreException.notFound("No such proposal"));
    }

    /** Replaces the proposal's lines with the user's corrected ones. */
    @PutMapping("/{id}/lines")
    PendingView edit(@PathVariable long id, @RequestParam long userId, @RequestBody EditBody body) {
        if (body.lines() == null) {
            throw CoreException.invalid("At least one line is required");
        }
        // A line the user has looked at and fixed is no longer a guess: confidence 1.
        List<ProposedLine> lines = body.lines().stream()
                .map(l -> new ProposedLine(l.rawText() != null ? l.rawText() : l.name(),
                        resolver.ingredientId(l.ingredientId(), l.name()), l.quantity(), l.unit(), l.unitPrice(), 1.0))
                .toList();
        return queue.edit(id, userId, lines);
    }

    @PostMapping("/{id}/confirm")
    ConfirmResult confirm(@PathVariable long id, @RequestParam long userId,
            @RequestParam(defaultValue = "true") boolean strict) {
        return queue.confirm(id, userId, strict);
    }

    @PostMapping("/{id}/reject")
    PendingView reject(@PathVariable long id, @RequestParam long userId) {
        return queue.reject(id, userId);
    }
}
