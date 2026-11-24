package com.pantrypilot.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

final class DashboardClient {
    record Data(JsonNode analytics, JsonNode shopping, JsonNode expiry, JsonNode history, JsonNode recipes) {}
    private final URI base;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    DashboardClient(URI base) { this.base = base; }

    Data load(long household, long user) throws IOException, InterruptedException {
        String insights = insights(household);
        String actor = "?userId=" + user;
        return new Data(read(insights + "/analytics" + actor), read(insights + "/shopping-list" + actor),
                read("/api/core/households/" + household + "/expiring" + actor + "&days=7"),
                read(insights + "/history" + actor), read(insights + "/cook-now" + actor));
    }

    void threshold(long household, long user, String ingredient, String unit, BigDecimal minimum) throws Exception {
        send("PUT", insights(household) + "/thresholds?userId=" + user,
                Map.of("ingredient", ingredient, "unit", unit, "minimum", minimum));
    }

    String subscribe(long household, long user, boolean google) throws Exception {
        var response = send("PUT", insights(household) + "/calendar?userId=" + user + "&google=" + google, null);
        return base.resolve(response.get("path").asText()).toString();
    }

    void removeThreshold(long household, long user, String ingredient) throws Exception {
        send("DELETE", insights(household) + "/thresholds?userId=" + user + "&ingredient="
                + URLEncoder.encode(ingredient, StandardCharsets.UTF_8), null);
    }

    void unsubscribe(long household, long user) throws Exception {
        send("DELETE", insights(household) + "/calendar?userId=" + user, null);
    }

    JsonNode recipe(long household, long user, String url) throws Exception {
        return read(insights(household) + "/cook-now?userId=" + user + "&url=" + URLEncoder.encode(url, StandardCharsets.UTF_8));
    }

    private static String insights(long household) { return "/api/insights/households/" + household; }

    private JsonNode read(String path) throws IOException, InterruptedException {
        return send("GET", path, null);
    }

    private JsonNode send(String method, String path, Object body) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofMinutes(2))
                .header("Accept", "application/json");
        if (body == null) req.method(method, HttpRequest.BodyPublishers.noBody());
        else req.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        var response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            String message = switch (response.statusCode()) {
                case 403 -> "This user is not a member of the household";
                case 400 -> "Check the submitted values";
                case 503 -> "Service unavailable. For direct Google sync, check the member's calendar credentials.";
                default -> "Request failed (" + response.statusCode() + ")";
            };
            throw new IOException(message);
        }
        return response.body().isBlank() ? mapper.nullNode() : mapper.readTree(response.body());
    }
}
