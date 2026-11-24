package com.pantrypilot.insights.internal;

import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class GoogleReminders {
    private record Event(long household, long user, String id, LocalDate day, String description) {}
    private static final Logger log = LoggerFactory.getLogger(GoogleReminders.class);
    private final JdbcClient db;
    private final GoogleCalendarApi api;

    GoogleReminders(JdbcClient db, GoogleCalendarApi api) {
        this.db = db;
        this.api = api;
    }

    boolean configured(long user) { return api.configured(user); }

    void sync(long household, long user) {
        if (!api.configured(user)) return;
        var desired = db.sql("""
                select description, run_date from insights_calendar where household_id=? and user_id=?
                and google_enabled=true and description<>''
                """).params(household,user).query((rs,row) -> new Event(household,user,null,
                        rs.getObject(2, LocalDate.class), rs.getString(1))).optional();
        if (desired.isEmpty()) return;
        var target = desired.get();
        var saved = db.sql("select event_id, sent_date, sent_description from insights_google_event where household_id=? and user_id=?")
                .params(household,user).query((rs,row) -> new Event(household,user,rs.getString(1),
                        rs.getObject(2, LocalDate.class),rs.getString(3))).optional();
        if (saved.isPresent() && target.day().equals(saved.get().day()) && target.description().equals(saved.get().description())) return;
        String id;
        if (saved.isPresent()) {
            id = saved.get().id();
        } else {
            id = UUID.randomUUID().toString().replace("-", "");
            db.sql("insert into insights_google_event (household_id,user_id,event_id) values (?,?,?)").params(household,user,id).update();
        }
        api.save(user,id,target.day(),target.description());
        db.sql("update insights_google_event set sent_date=?,sent_description=? where event_id=?")
                .params(target.day(),target.description(),id).update();
    }

    void removeObsolete() {
        var obsolete = db.sql("""
                select e.household_id,e.user_id,e.event_id from insights_google_event e
                where not exists (select 1 from insights_calendar c where c.household_id=e.household_id
                and c.user_id=e.user_id and c.google_enabled=true and c.description<>'')
                """).query((rs,row) -> new Event(rs.getLong(1),rs.getLong(2),rs.getString(3),null,null)).list();
        for (var event : obsolete) {
            if (!api.configured(event.user())) continue;
            try {
                api.remove(event.user(), event.id());
                db.sql("delete from insights_google_event where event_id=?").param(event.id()).update();
            } catch (RuntimeException unavailable) {
                log.warn("Grocery reminder removal will retry for household {}", event.household());
            }
        }
    }
}
