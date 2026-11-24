package com.pantrypilot.insights.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class GoogleCalendarApi {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Environment config;
    private final ObjectMapper json;
    private final URI tokens;
    private final URI events;

    @Autowired
    GoogleCalendarApi(Environment config, ObjectMapper json) {
        this(config, json, URI.create("https://oauth2.googleapis.com/token"),
                URI.create("https://www.googleapis.com/calendar/v3/calendars/primary/events"));
    }

    GoogleCalendarApi(Environment config, ObjectMapper json, URI tokens, URI events) {
        this.config = config;
        this.json = json;
        this.tokens = tokens;
        this.events = events;
    }

    boolean configured(long user) {
        return present("PANTRYPILOT_GOOGLE_CLIENT_ID") && present("PANTRYPILOT_GOOGLE_CLIENT_SECRET")
                && present("PANTRYPILOT_GOOGLE_REFRESH_TOKEN_" + user);
    }

    void save(long user, String id, LocalDate day, String description) {
        var payload = Map.of("id", id, "summary", "PantryPilot grocery run", "description", description,
                "start", Map.of("date", day.toString()), "end", Map.of("date", day.plusDays(1).toString()),
                "reminders", Map.of("useDefault", false, "overrides", List.of(Map.of("method","popup","minutes",720))));
        try {
            String body = json.writeValueAsString(payload);
            String token = accessToken(user);
            URI event = URI.create(events + "/" + id);
            int status = write("PUT", event, token, body);
            if (status == 404) {
                status = write("POST", events, token, body);
                if (status == 409) status = write("PUT", event, token, body);
            }
            requireSuccess(status);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void remove(long user, String id) {
        try {
            int status = write("DELETE", URI.create(events + "/" + id), accessToken(user), null);
            if (status != 404 && status != 410) requireSuccess(status);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean present(String key) {
        return !config.getProperty(key, "").isBlank();
    }

    private String accessToken(long user) throws IOException {
        String form = "grant_type=refresh_token&client_id=" + encoded("PANTRYPILOT_GOOGLE_CLIENT_ID")
                + "&client_secret=" + encoded("PANTRYPILOT_GOOGLE_CLIENT_SECRET")
                + "&refresh_token=" + encoded("PANTRYPILOT_GOOGLE_REFRESH_TOKEN_" + user);
        var request = HttpRequest.newBuilder(tokens).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response = send(request);
        requireSuccess(response.statusCode());
        String value = json.readTree(response.body()).path("access_token").asText();
        if (value.isBlank()) throw new IllegalStateException("Google did not return an access token");
        return value;
    }

    private String encoded(String key) {
        return URLEncoder.encode(config.getRequiredProperty(key), StandardCharsets.UTF_8);
    }

    private int write(String method, URI uri, String token, String body) throws IOException {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return send(request.build()).statusCode();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Calendar sync interrupted", interrupted);
        }
    }

    private static void requireSuccess(int status) {
        if (status / 100 != 2) throw new IllegalStateException("Google Calendar returned HTTP " + status);
    }
}
