package com.pantrypilot.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class InsightsFeaturesTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    JsonNode request(MockHttpServletRequestBuilder req, String body) throws Exception {
        if (body != null) req.contentType(APPLICATION_JSON).content(body);
        String response = mvc.perform(req.param("userId", "1")).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    long newHousehold() throws Exception {
        return request(post("/api/core/households"), "{\"name\":\"Insights test\"}").get("id").asLong();
    }

    @Test
    void reportsActualCostsUsageAndUndo() throws Exception {
        long id = newHousehold();
        String core = "/api/core/households/" + id;
        String report = "/api/insights/households/" + id + "/analytics";
        request(post(core + "/stock-in"), """
                {"lines":[{"name":"tomato","quantity":10,"unit":"COUNT","unitPrice":1.25}]}
                """);
        request(put("/api/ai/price-estimate"), """
                {"dish":"Insights soup","city":"New York","pricePerServing":10}
                """);
        long cook = request(post(core + "/consume"), """
                {"dishName":"Insights soup","servings":2,"lines":[{"name":"tomato","quantity":7,"unit":"COUNT"}]}
                """).get("txnId").asLong();
        JsonNode first = request(get(report), null);
        assertThat(first.path("savings").decimalValue()).isEqualByComparingTo("19.03");
        assertThat(first.at("/usage/0/dailyAverage").decimalValue()).isEqualByComparingTo("1");
        long lot = request(get(core + "/pantry"), null).get(0).get("lotId").asLong();
        request(post(core + "/lots/" + lot + "/discard"), "{\"quantity\":3}");
        assertThat(request(get(report), null).path("wasteCost").decimalValue()).isEqualByComparingTo("3.75");
        request(post(core + "/changes/" + cook + "/undo"), null);
        JsonNode after = request(get(report), null);
        assertThat(after.path("usage").size()).isZero();
        assertThat(after.path("savings").decimalValue()).isEqualByComparingTo("0");
        mvc.perform(get(report).param("userId", "99999")).andExpect(status().isForbidden());
    }

    @Test
    void anotherMemberCannotSeePrivateLotHistoryOrUsage() throws Exception {
        JsonNode home = request(post("/api/core/households"), "{\"name\":\"History privacy\"}");
        long id = home.path("id").asLong();
        JsonNode member = request(post("/api/core/users"), "{\"displayName\":\"Pat\",\"email\":\"history" + id + "@example.org\"}");
        String other = member.path("id").asText();
        mvc.perform(post("/api/core/households/join").param("userId", other).contentType(APPLICATION_JSON)
                .content("{\"inviteCode\":\"" + home.path("inviteCode").asText() + "\"}")).andExpect(status().isOk());
        request(post("/api/core/households/" + id + "/stock-in"), """
                {"lines":[{"name":"tomato","quantity":2,"unit":"COUNT","personal":true}]}
                """);
        request(post("/api/core/households/" + id + "/consume"), """
                {"lines":[{"name":"tomato","quantity":1,"unit":"COUNT","personal":true}]}
                """);
        String root = "/api/insights/households/" + id;
        assertThat(request(get(root + "/history"), null).size()).isEqualTo(2);
        mvc.perform(get(root + "/history").param("userId", other)).andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get(root + "/analytics").param("userId", other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.usage").isEmpty());
    }

    @Test
    void recipeSuggestionsReflectCurrentStockAndNeverDeduct() throws Exception {
        long id = newHousehold();
        String url = "/api/insights/households/" + id + "/cook-now";
        assertThat(request(get(url), null).size()).isZero();
        request(post("/api/core/households/" + id + "/stock-in"), """
                {"lines":[{"name":"egg","quantity":2,"unit":"COUNT"}]}
                """);
        assertThat(request(get(url), null).findValuesAsText("title")).contains("Scrambled eggs");
        assertThat(request(get(url), null).findValuesAsText("title")).contains("Scrambled eggs");
    }

    @Test
    void anUnknownConversionKeepsTheOtherShortagesUsable() throws Exception {
        long id = newHousehold();
        String root = "/api/insights/households/" + id;
        request(put(root + "/thresholds"), "{\"ingredient\":\"tomato\",\"unit\":\"ML\",\"minimum\":20}");
        request(put(root + "/thresholds"), "{\"ingredient\":\"egg\",\"unit\":\"COUNT\",\"minimum\":2}");
        request(post("/api/core/households/" + id + "/stock-in"), """
                {"lines":[{"name":"tomato","quantity":1,"unit":"COUNT"}]}
                """);
        JsonNode list = request(get(root + "/shopping-list"), null);
        assertThat(list.at("/0/needed").decimalValue()).isEqualByComparingTo("2");
        assertThat(list.at("/1/needed").isNull()).isTrue();
        String feed = request(put(root + "/calendar"), null).path("path").asText();
        String ics = mvc.perform(get(feed)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ics).contains("egg 2 COUNT").doesNotContain("tomato");
    }

    @Test
    void calendarOptInTracksLowStockAndOptOutRevokesFeed() throws Exception {
        long id = newHousehold();
        String root = "/api/insights/households/" + id;
        mvc.perform(put(root + "/calendar").param("userId", "99999")).andExpect(status().isForbidden());
        String feed = request(put(root + "/calendar"), null).get("path").asText();
        mvc.perform(get(feed)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("BEGIN:VEVENT"))));
        request(put(root + "/thresholds"), "{\"ingredient\":\"tomato\",\"unit\":\"COUNT\",\"minimum\":2}");
        String event = mvc.perform(get(feed)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(event).contains("BEGIN:VEVENT", "tomato", "BEGIN:VALARM");
        request(delete(root + "/calendar"), null);
        mvc.perform(get(feed)).andExpect(status().isNotFound());
    }
}
