package com.pantrypilot.insights.internal;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class CalendarController {
    private final GroceryCalendar calendars;
    CalendarController(GroceryCalendar calendars) {
        this.calendars = calendars;
    }

    @PutMapping("/api/insights/households/{household}/calendar")
    ResponseEntity<Map<String, String>> subscribe(@PathVariable long household, @RequestParam long userId,
                                                  @RequestParam(defaultValue = "false") boolean google) {
        String token = calendars.subscribe(household, userId, google);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("path", "/api/insights/calendar/" + token + ".ics"));
    }

    @DeleteMapping("/api/insights/households/{household}/calendar")
    void unsubscribe(@PathVariable long household, @RequestParam long userId) {
        calendars.unsubscribe(household, userId);
    }

    @GetMapping(value="/api/insights/calendar/{token}.ics", produces="text/calendar;charset=UTF-8")
    ResponseEntity<String> feed(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(calendars.feed(token));
    }
}
