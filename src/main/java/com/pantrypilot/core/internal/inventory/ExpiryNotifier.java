package com.pantrypilot.core.internal.inventory;

import com.pantrypilot.shared.events.ItemExpiringSoon;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Once a day, tells the rest of the server which items expire today or in two
 * days. The core module only announces it; what to do about it (a reminder, a
 * shopping-list entry) is the insights module's job.
 */
@Component
public class ExpiryNotifier {

    static final int WARNING_DAYS = 2;

    @Configuration
    @EnableScheduling
    static class Scheduling {
    }

    private final LotRepository lots;
    private final ApplicationEventPublisher publisher;

    public ExpiryNotifier(LotRepository lots, ApplicationEventPublisher publisher) {
        this.lots = lots;
        this.publisher = publisher;
    }

    @Scheduled(cron = "${pantrypilot.core.expiry-check-cron:0 0 8 * * *}")
    void daily() {
        publishDue(LocalDate.now());
    }

    /** @return how many events were published: one per household, ingredient and date */
    public int publishDue(LocalDate today) {
        record Key(long householdId, long ingredientId, LocalDate expiresOn) {
        }
        Set<Key> due = new LinkedHashSet<>();
        for (Lot lot : lots.expiringOn(List.of(today, today.plusDays(WARNING_DAYS)))) {
            due.add(new Key(lot.getHouseholdId(), lot.getIngredientId(), lot.getExpiresOn()));
        }
        Instant now = Instant.now();
        due.forEach(k -> publisher.publishEvent(new ItemExpiringSoon(k.householdId(), k.ingredientId(), k.expiresOn(), now)));
        return due.size();
    }
}
