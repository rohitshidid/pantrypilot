package com.pantrypilot.insights.internal;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class GroceryCalendar {
    record Subscription(long household, long user, String token) {}
    private static final Logger log = LoggerFactory.getLogger(GroceryCalendar.class);
    private final JdbcClient db;
    private final HouseholdPantry pantry;
    private final ShoppingLists lists;
    private final GoogleReminders google;

    GroceryCalendar(JdbcClient db, HouseholdPantry pantry, ShoppingLists lists, GoogleReminders google) {
        this.db = db;
        this.pantry = pantry;
        this.lists = lists;
        this.google = google;
    }

    String subscribe(long household, long user, boolean googleSync) {
        pantry.visible(household, user);
        if (googleSync && !google.configured(user))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google Calendar is not configured for this member");
        String token = ensureSubscription(household, user);
        if (googleSync) db.sql("update insights_calendar set google_enabled=true where token=?").param(token).update();
        return token;
    }

    private String ensureSubscription(long household, long user) {
        var find = "select token from insights_calendar where household_id=? and user_id=?";
        var existing = db.sql(find).params(household, user).query(String.class).optional();
        if (existing.isPresent()) return existing.get();
        String token = UUID.randomUUID().toString();
        try {
            db.sql("insert into insights_calendar (household_id,user_id,token,updated_at) values (?,?,?,CURRENT_TIMESTAMP)")
                    .params(household, user, token).update();
        } catch (DuplicateKeyException concurrent) {
            return db.sql(find).params(household, user).query(String.class).single();
        }
        return token;
    }

    void unsubscribe(long household, long user) {
        pantry.visible(household, user);
        db.sql("delete from insights_calendar where household_id=? and user_id=?").params(household,user).update();
    }

    @Scheduled(fixedDelayString = "${pantrypilot.insights.calendar-refresh:PT15M}", initialDelayString = "PT1M")
    void refreshAll() {
        var subscriptions = db.sql("select household_id,user_id,token from insights_calendar")
                .query((rs,row) -> new Subscription(rs.getLong(1), rs.getLong(2), rs.getString(3))).list();
        for (var sub : subscriptions) {
            try {
                refresh(sub);
                google.sync(sub.household(), sub.user());
            } catch (RuntimeException unavailable) {
                log.warn("Calendar refresh unavailable for household {}", sub.household());
            }
        }
        google.removeObsolete();
    }

    private void refresh(Subscription sub) {
        List<ShoppingLists.Item> items;
        try {
            items = lists.shopping(sub.household(), sub.user());
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode() != HttpStatus.FORBIDDEN) throw denied;
            db.sql("delete from insights_calendar where token=?").param(sub.token()).update();
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String description = String.join(", ", items.stream().filter(item -> item.needed() != null).map(item ->
                item.name() + " " + item.needed().stripTrailingZeros().toPlainString() + " " + item.unit()).toList());
        LocalDate today = LocalDate.now();
        LocalDate due = db.sql("select run_date from insights_calendar where token=? and description<>'' and run_date>=?")
                .params(sub.token(), today).query(LocalDate.class).optional().orElse(today.plusDays(1));
        db.sql("""
                update insights_calendar set description=?, run_date=?, updated_at=CURRENT_TIMESTAMP
                where token=? and (run_date is null or description<>? or run_date<>?)
                """).params(description, due, sub.token(), description, due).update();
    }

    String feed(String token) {
        var sub = db.sql("select household_id,user_id from insights_calendar where token=?").param(token)
                .query((rs,row) -> new Subscription(rs.getLong(1), rs.getLong(2), token)).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        refresh(sub);
        return db.sql("select description,run_date,updated_at from insights_calendar where token=?").param(token)
                .query((rs,row) -> render(sub, rs.getString(1), rs.getObject(2, LocalDate.class),
                        rs.getTimestamp(3).toInstant())).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String render(Subscription sub, String list, LocalDate day, Instant updated) {
        List<String> lines = new ArrayList<>(List.of("BEGIN:VCALENDAR","VERSION:2.0","PRODID:-//PantryPilot//Grocery Run//EN",
                "CALSCALE:GREGORIAN","X-WR-CALNAME:PantryPilot groceries"));
        if (!list.isEmpty()) {
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(updated);
            lines.addAll(List.of("BEGIN:VEVENT","UID:groceries-" + sub.household() + "-" + sub.user() + "@pantrypilot",
                    "DTSTAMP:" + stamp, "LAST-MODIFIED:" + stamp,
                    "DTSTART;VALUE=DATE:" + day.format(DateTimeFormatter.BASIC_ISO_DATE),
                    "DTEND;VALUE=DATE:" + day.plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE),
                    "SUMMARY:Grocery run", "DESCRIPTION:" + list.replace("\\","\\\\").replace(";","\\;")
                            .replace(",","\\,").replace("\r","").replace("\n","\\n"),
                    "BEGIN:VALARM","ACTION:DISPLAY","TRIGGER:-PT12H","DESCRIPTION:Check the shared shopping list",
                    "END:VALARM","END:VEVENT"));
        }
        lines.add("END:VCALENDAR");
        StringBuilder output = new StringBuilder();
        for (String line : lines) {
            int bytes = 0;
            for (int cp : line.codePoints().toArray()) {
                int size = Character.toString(cp).getBytes(StandardCharsets.UTF_8).length;
                if (bytes + size > 75) {
                    output.append("\r\n ");
                    bytes = 1;
                }
                output.appendCodePoint(cp);
                bytes += size;
            }
            output.append("\r\n");
        }
        return output.toString();
    }
}
