package com.pantrypilot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pantrypilot.shared.Direction;
import com.pantrypilot.shared.PendingChangeService;
import com.pantrypilot.shared.ProposedChange;
import com.pantrypilot.shared.ProposedLine;
import com.pantrypilot.shared.Source;
import com.pantrypilot.shared.Unit;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The core module through its REST API, the way the phone app and the dashboard will use it. */
@SpringBootTest
@AutoConfigureMockMvc
class CoreApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private PendingChangeService submissions;

    private JsonNode json(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String body = mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : mapper.readTree(body);
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String json) {
        return request.contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private long newUser(String name) throws Exception {
        return json(body(post("/api/core/users"),
                "{\"displayName\":\"" + name + "\",\"email\":\"" + name + System.nanoTime() + "@example.com\"}"), 201)
                .path("id").asLong();
    }

    @Test
    void theWholeLoopFromHouseholdToUndo() throws Exception {
        // two people share one kitchen
        long asha = newUser("asha");
        long ben = newUser("ben");
        JsonNode household = json(body(post("/api/core/households").param("userId", "" + asha), "{\"name\":\"Flat 4B\"}"), 201);
        long h = household.path("id").asLong();
        assertThat(household.path("role").asText()).isEqualTo("ADMIN");
        json(body(post("/api/core/households/join").param("userId", "" + ben),
                "{\"inviteCode\":\"" + household.path("inviteCode").asText() + "\"}"), 200);
        assertThat(json(get("/api/core/households/" + h + "/members").param("userId", "" + ben), 200)).hasSize(2);
        String home = "/api/core/households/" + h;

        // manual entry, by name
        JsonNode added = json(body(post(home + "/stock-in").param("userId", "" + asha), """
                {"lines":[{"name":"tomatoes","quantity":6,"unit":"COUNT","unitPrice":0.5},
                          {"name":"whole milk","quantity":1000,"unit":"ML","location":"FRIDGE"}]}"""), 200);
        assertThat(json(get(home + "/pantry").param("userId", "" + ben), 200)).hasSize(2);
        JsonNode stock = json(get(home + "/stock").param("userId", "" + ben), 200);
        assertThat(stock.get(0).path("name").asText()).isEqualTo("tomato");   // soonest expiry first
        assertThat(stock.get(0).path("grams").decimalValue()).isEqualByComparingTo("720");
        assertThat(stock.get(1).path("name").asText()).isEqualTo("milk");

        // an AI proposal arrives, Ben corrects the amount and confirms it
        long pendingId = submissions.submit(new ProposedChange(h, asha, Source.LINK, Direction.OUT, "Tomato soup", 2,
                List.of(new ProposedLine("8 tomatoes", 1L, new BigDecimal("8"), Unit.COUNT, null, 0.8))));
        mvc.perform(post("/api/core/pending/" + pendingId + "/confirm").param("userId", "" + ben))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("Not enough tomato")));
        json(body(put("/api/core/pending/" + pendingId + "/lines").param("userId", "" + ben),
                "{\"lines\":[{\"rawText\":\"8 tomatoes\",\"name\":\"tomato\",\"quantity\":4,\"unit\":\"COUNT\"}]}"), 200);
        JsonNode confirmed = json(post("/api/core/pending/" + pendingId + "/confirm").param("userId", "" + ben), 200);
        assertThat(confirmed.path("cost").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(confirmed.path("pending").path("state").asText()).isEqualTo("CONFIRMED");

        // history, then undo
        JsonNode history = json(get(home + "/history").param("userId", "" + asha), 200);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).path("dishName").asText()).isEqualTo("Tomato soup");
        assertThat(history.get(0).path("userName").asText()).isEqualTo("ben");
        long txnId = confirmed.path("txnId").asLong();
        json(post(home + "/changes/" + txnId + "/undo").param("userId", "" + ben), 200);
        mvc.perform(post(home + "/changes/" + txnId + "/undo").param("userId", "" + ben)).andExpect(status().isConflict());
        mvc.perform(post(home + "/changes/" + added.path("txnId").asLong() + "/undo").param("userId", "" + ben))
                .andExpect(status().isForbidden());   // Asha's entry, and Ben is not an admin

        // leftovers and discarding
        json(body(post(home + "/leftovers").param("userId", "" + asha), "{\"name\":\"Tomato soup\",\"servings\":2}"), 200);
        JsonNode expiring = json(get(home + "/expiring").param("userId", "" + asha).param("days", "4"), 200);
        assertThat(expiring).hasSize(1);
        long leftoverLot = expiring.get(0).path("lotId").asLong();
        json(body(post(home + "/lots/" + leftoverLot + "/discard").param("userId", "" + asha), "{\"reason\":\"forgot it\"}"), 200);
        assertThat(json(get(home + "/pantry").param("userId", "" + asha), 200)).hasSize(2);

        // leaving
        mvc.perform(delete(home + "/members/" + ben).param("userId", "" + ben)).andExpect(status().isNoContent());
        mvc.perform(get(home + "/pantry").param("userId", "" + ben)).andExpect(status().isForbidden());
    }

    @Test
    void errorsAreClearAndUseTheRightStatus() throws Exception {
        long user = newUser("solo");
        long h = json(body(post("/api/core/households").param("userId", "" + user), "{\"name\":\"Solo\"}"), 201)
                .path("id").asLong();
        String home = "/api/core/households/" + h;

        mvc.perform(get(home + "/pantry")).andExpect(status().isBadRequest());                       // who is asking?
        mvc.perform(get(home + "/pantry").param("userId", "999999")).andExpect(status().isForbidden());
        mvc.perform(body(post(home + "/stock-in").param("userId", "" + user),
                        "{\"lines\":[{\"name\":\"unobtainium\",\"quantity\":1,\"unit\":\"COUNT\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("not in the catalog")));
        mvc.perform(body(post(home + "/stock-in").param("userId", "" + user),
                        "{\"lines\":[{\"name\":\"rice\",\"quantity\":1,\"unit\":\"CUPS\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(body(post(home + "/stock-in").param("userId", "" + user), "{\"lines\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/core/pending/424242").param("userId", "" + user)).andExpect(status().isNotFound());
        mvc.perform(body(post("/api/core/users"), "{\"displayName\":\"x\",\"email\":\"nope\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theCatalogCanBeSearchedMatchedAndConverted() throws Exception {
        assertThat(json(get("/api/core/catalog").param("q", "tamatar"), 200).get(0).path("name").asText())
                .isEqualTo("tomato");
        assertThat(json(get("/api/core/catalog"), 200).size()).isGreaterThanOrEqualTo(80);
        JsonNode match = json(get("/api/core/catalog/resolve").param("name", "2 large onions"), 200);
        assertThat(match.path("canonicalName").asText()).isEqualTo("onion");
        mvc.perform(get("/api/core/catalog/resolve").param("name", "unobtainium")).andExpect(status().isNotFound());
        JsonNode grams = json(get("/api/core/catalog/convert").param("ingredientId", match.path("ingredientId").asText())
                .param("quantity", "2").param("from", "COUNT").param("to", "G"), 200);
        assertThat(grams.path("quantity").decimalValue()).isEqualByComparingTo("300");
        mvc.perform(get("/api/core/catalog/convert").param("ingredientId", match.path("ingredientId").asText())
                .param("quantity", "2").param("from", "COUNT").param("to", "ML")).andExpect(status().isBadRequest());
    }
}
