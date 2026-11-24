package com.pantrypilot.insights.internal;

import com.pantrypilot.shared.Unit;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** The existing pantry API checks membership and marks each lot's visibility. */
@Component
class HouseholdPantry {
    record Lot(long lotId, long ingredientId, String name, BigDecimal quantity, Unit unit,
               BigDecimal unitPrice, boolean personal) {}

    private final RestClient client;
    private final Environment environment;

    HouseholdPantry(RestClient.Builder builder, Environment environment) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        client = builder.requestFactory(factory).build();
        this.environment = environment;
    }

    List<Lot> visible(long household, long user) {
        return List.of(read(household, user, "pantry", Lot[].class));
    }

    List<Analytics.Transaction> history(long household, long user) {
        return List.of(read(household, user, "history", Analytics.Transaction[].class));
    }

    private <T> T read(long household, long user, String resource, Class<T> type) {
        int port = environment.getRequiredProperty("local.server.port", Integer.class);
        return client.get()
                .uri("http://127.0.0.1:{port}/api/core/households/{household}/{resource}?userId={user}&limit=200",
                        port, household, resource, user)
                .retrieve()
                .onStatus(status -> status.value() == 403, (request, response) -> {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Household membership required");
                }).body(type);
    }
}
