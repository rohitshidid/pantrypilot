package com.pantrypilot.insights.internal;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GoogleRemindersTest {
    @Autowired GroceryCalendar calendars;
    @Autowired JdbcClient db;
    @MockitoBean GoogleCalendarApi google;
    @MockitoBean ShoppingLists lists;

    @Test
    void scheduledSyncCreatesOneReminderUpdatesItAndRemovesItAfterOptOut() {
        long household = 88567;
        long user = 71;
        db.sql("insert into insights_calendar(household_id,user_id,token,updated_at,google_enabled) values (?,?,?,CURRENT_TIMESTAMP,true)")
                .params(household, user, "test-calendar-token").update();
        when(google.configured(user)).thenReturn(true);
        when(lists.shopping(household,user)).thenReturn(java.util.List.of(new ShoppingLists.Item("egg",
                com.pantrypilot.shared.Unit.COUNT, java.math.BigDecimal.TEN, java.math.BigDecimal.ONE, new java.math.BigDecimal("9"))));
        calendars.refreshAll();
        calendars.refreshAll();
        var id = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(google).save(eq(user), id.capture(), any(LocalDate.class), eq("egg 9 COUNT"));
        assertThat(id.getValue()).matches("[0-9a-f]{32}");
        when(lists.shopping(household,user)).thenReturn(java.util.List.of(new ShoppingLists.Item("egg",
                com.pantrypilot.shared.Unit.COUNT, java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO, java.math.BigDecimal.TEN)));
        calendars.refreshAll();
        verify(google).save(eq(user), eq(id.getValue()), any(LocalDate.class), eq("egg 10 COUNT"));
        db.sql("delete from insights_calendar where household_id=?").param(household).update();
        calendars.refreshAll();
        calendars.refreshAll();
        verify(google).remove(user, id.getValue());
    }
}
