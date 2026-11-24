package com.pantrypilot.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pantrypilot.shared.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ShoppingListTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PendingChangeService queue;

    @Test
    void aMemberCanRemoveAThresholdEvenWhenStockIsEmpty() throws Exception {
        String home = mvc.perform(post("/api/core/households").param("userId", "1")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"Remove rule\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String insights = "/api/insights/households/" + mapper.readTree(home).path("id").asLong();
        mvc.perform(put(insights + "/thresholds").param("userId","1").contentType(APPLICATION_JSON)
                .content("{\"ingredient\":\"tomato\",\"unit\":\"COUNT\",\"minimum\":3}")).andExpect(status().isOk());
        mvc.perform(delete(insights + "/thresholds").param("userId","99999").param("ingredient","tomato"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(insights + "/thresholds").param("userId","1").param("ingredient","tomatoes"))
                .andExpect(status().isNoContent());
        mvc.perform(get(insights + "/shopping-list").param("userId","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void confirmedUseCrossesThresholdAndUndoClearsList() throws Exception {
        String home = mvc.perform(post("/api/core/households").param("userId", "1")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"Shopping list test\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long household = mapper.readTree(home).get("id").asLong();
        String core = "/api/core/households/" + household;
        String insights = "/api/insights/households/" + household;
        mvc.perform(post(core + "/stock-in").param("userId", "1").contentType(APPLICATION_JSON)
                .content("""
                        {"lines":[{"name":"tomato","unit":"COUNT","quantity":4},
                                  {"name":"tomato","unit":"COUNT","quantity":10,"personal":true}]}
                        """)).andExpect(status().isOk());
        mvc.perform(put(insights + "/thresholds").param("userId", "1").contentType(APPLICATION_JSON)
                .content("""
                        {"ingredient":"tomato","unit":"COUNT","minimum":3}
                        """)).andExpect(status().isOk());
        long pending = queue.submit(new ProposedChange(household, 1, Source.LINK, Direction.OUT, "Soup", 1,
                List.of(new ProposedLine("2 tomatoes", 1L, new BigDecimal("2"), Unit.COUNT, null, 1))));
        mvc.perform(get(insights + "/shopping-list").param("userId", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        String confirmed = mvc.perform(post("/api/core/pending/" + pending + "/confirm").param("userId", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(get(insights + "/shopping-list").param("userId", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("tomato"))
                .andExpect(jsonPath("$[0].needed").value(1));
        mvc.perform(get(insights + "/shopping-list").param("userId", "999999")).andExpect(status().isForbidden());
        long txn = mapper.readTree(confirmed).get("txnId").asLong();
        mvc.perform(post(core + "/changes/" + txn + "/undo").param("userId", "1")).andExpect(status().isOk());
        mvc.perform(get(insights + "/shopping-list").param("userId", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }
}
