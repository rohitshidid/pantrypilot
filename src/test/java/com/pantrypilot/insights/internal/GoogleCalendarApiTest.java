package com.pantrypilot.insights.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class GoogleCalendarApiTest {
    @Test
    void refreshesConsentAndWritesAnAllDayEventThroughCalendarV3() throws Exception {
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> methods = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        var exists = new AtomicBoolean();
        server.createContext("/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean consent = "POST".equals(exchange.getRequestMethod()) && form.contains("refresh_token=test%2Bconsent");
            byte[] body = "{\"access_token\":\"local-fixture\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(consent ? 200 : 400, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/events", exchange -> {
            String method = exchange.getRequestMethod();
            methods.add(method);
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = switch (method) {
                case "PUT" -> exists.get() ? 200 : 404;
                case "POST" -> exists.getAndSet(true) ? 409 : 201;
                case "DELETE" -> exists.getAndSet(false) ? 204 : 410;
                default -> 405;
            };
            if (!"Bearer local-fixture".equals(exchange.getRequestHeaders().getFirst("Authorization"))) status = 401;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var config = new MockEnvironment().withProperty("PANTRYPILOT_GOOGLE_CLIENT_ID","fixture")
                    .withProperty("PANTRYPILOT_GOOGLE_CLIENT_SECRET","fixture")
                    .withProperty("PANTRYPILOT_GOOGLE_REFRESH_TOKEN_3","test+consent");
            var api = new GoogleCalendarApi(config, mapper, base.resolve("/token"), base.resolve("/events"));
            assertThat(api.configured(4)).isFalse();
            LocalDate day = LocalDate.of(2026,11,24);
            api.save(3, "abc123", day, "egg 2 COUNT");
            api.save(3, "abc123", day, "milk 500 ML");
            api.remove(3, "abc123");
            api.remove(3, "abc123");
            assertThat(methods).containsExactly("PUT","POST","PUT","DELETE","DELETE");
            var event = mapper.readTree(requests.get(1));
            assertThat(event.get("id").asText()).isEqualTo("abc123");
            assertThat(event.at("/start/date").asText()).isEqualTo("2026-11-24");
            assertThat(event.at("/end/date").asText()).isEqualTo("2026-11-25");
            assertThat(event.at("/reminders/overrides/0/method").asText()).isEqualTo("popup");
        } finally {
            server.stop(0);
        }
    }
}
