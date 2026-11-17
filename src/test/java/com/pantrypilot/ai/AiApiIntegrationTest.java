package com.pantrypilot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pantrypilot.ai.testsupport.FakeAiBackends;
import com.pantrypilot.ai.testsupport.ScriptedLlm;
import com.pantrypilot.ai.testsupport.TestImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The whole server through its REST API: upload or link in, job polled until
 * done, proposal in the review queue. Only the model, OCR and the web are faked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeAiBackends.class)
class AiApiIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private ScriptedLlm llm;

    @BeforeEach
    void reset() {
        llm.reset();
        FakeAiBackends.PAGES.clear();
        FakeAiBackends.ocrText = "";
    }

    private JsonNode json(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String body = mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : mapper.readTree(body);
    }

    private JsonNode awaitJob(JsonNode accepted) throws Exception {
        assertThat(accepted.path("status").asText()).isIn("QUEUED", "RUNNING", "SUCCEEDED", "FAILED");
        long id = accepted.path("id").asLong();
        for (int i = 0; i < 500; i++) {
            JsonNode job = json(get("/api/ai/jobs/" + id), 200);
            if (job.path("status").asText().matches("SUCCEEDED|FAILED")) {
                return job;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("job " + id + " did not finish");
    }

    /** The core module only shows and applies proposals to members, so each test gets a real household. */
    private record Kitchen(long householdId, long userId) {
    }

    private Kitchen kitchen() throws Exception {
        long userId = json(post("/api/core/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Tester\",\"email\":\"tester" + System.nanoTime() + "@example.com\"}"), 201)
                .path("id").asLong();
        long householdId = json(post("/api/core/households").param("userId", String.valueOf(userId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Test kitchen\"}"), 201)
                .path("id").asLong();
        return new Kitchen(householdId, userId);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Kitchen k) {
        return request.param("householdId", String.valueOf(k.householdId())).param("userId", String.valueOf(k.userId()));
    }

    private static MockMultipartFile image(String field) {
        return new MockMultipartFile(field, "photo.png", "image/png", TestImages.blankPng());
    }

    @Test
    void receiptGoesFromUploadToTheReviewQueue() throws Exception {
        FakeAiBackends.ocrText = "FRESH MART\nGV WHL MLK GAL 4.29\nTOTAL 4.29\n";
        llm.answer("""
                {"store":"Fresh Mart","purchaseDate":"2026-11-10","lines":[
                  {"rawText":"GV WHL MLK GAL","name":"whole milk","quantity":1,"unit":"gal","lineTotal":4.29,"confidence":0.9}]}""");

        Kitchen k = kitchen();
        JsonNode accepted = json(as(multipart("/api/ai/receipts").file(image("image")), k), 202);
        JsonNode job = awaitJob(accepted);

        assertThat(job.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(job.path("type").asText()).isEqualTo("RECEIPT");
        JsonNode proposal = job.path("result").path("proposal");
        assertThat(proposal.path("source").asText()).isEqualTo("RECEIPT");
        assertThat(proposal.path("direction").asText()).isEqualTo("IN");
        assertThat(proposal.path("householdId").asLong()).isEqualTo(k.householdId());
        assertThat(proposal.path("createdBy").asLong()).isEqualTo(k.userId());
        assertThat(proposal.path("lines").get(0).path("unit").asText()).isEqualTo("ML");
        assertThat(job.path("result").path("details").get(0).path("canonicalName").asText()).isEqualTo("milk");
        assertThat(job.path("result").path("meta").path("store").asText()).isEqualTo("Fresh Mart");

        // the proposal waits in the core module's queue; nothing was applied
        long pendingId = job.path("pendingChangeId").asLong();
        JsonNode queue = json(as(get("/api/core/pending"), k), 200);
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).path("id").asLong()).isEqualTo(pendingId);
        assertThat(queue.get(0).path("state").asText()).isEqualTo("PENDING");

        String user = String.valueOf(k.userId());
        mvc.perform(post("/api/core/pending/" + pendingId + "/confirm").param("userId", user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pending.state").value("CONFIRMED"));
        mvc.perform(post("/api/core/pending/" + pendingId + "/reject").param("userId", user))
                .andExpect(status().isConflict());

        // confirming is what puts the milk in the pantry
        JsonNode pantry = json(get("/api/core/households/" + k.householdId() + "/pantry").param("userId", user), 200);
        assertThat(pantry).hasSize(1);
        assertThat(pantry.get(0).path("name").asText()).isEqualTo("milk");

        assertThat(json(get("/api/ai/jobs").param("householdId", String.valueOf(k.householdId())), 200)).hasSize(1);
    }

    @Test
    void photosGoFromUploadToAProposal() throws Exception {
        llm.answer("{\"items\":[{\"name\":\"banana\",\"count\":5,\"estimatedWeightGrams\":600,\"confidence\":0.9}]}");

        JsonNode job = awaitJob(json(multipart("/api/ai/photos").file(image("images")).file(image("images"))
                .param("householdId", "22").param("userId", "5"), 202));

        assertThat(job.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(llm.requests()).hasSize(2);
        JsonNode lines = job.path("result").path("proposal").path("lines");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).path("quantity").asInt()).isEqualTo(5);
        assertThat(job.path("result").path("meta").path("photos").asInt()).isEqualTo(2);
    }

    @Test
    void recipeLinkBecomesAScaledDeduction() throws Exception {
        FakeAiBackends.PAGES.put("https://recipes.example/dal", """
                <html><head><script type="application/ld+json">
                {"@type":"Recipe","name":"Dal","recipeYield":"4","recipeIngredient":["200 g red lentils","1 onion"]}
                </script></head><body></body></html>""");

        Kitchen k = kitchen();
        JsonNode job = awaitJob(json(post("/api/ai/recipe-links").contentType(MediaType.APPLICATION_JSON).content("""
                {"url":"https://recipes.example/dal","servingsMade":2,"householdId":%d,"userId":%d}"""
                .formatted(k.householdId(), k.userId())), 202));

        assertThat(job.path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode proposal = job.path("result").path("proposal");
        assertThat(proposal.path("direction").asText()).isEqualTo("OUT");
        assertThat(proposal.path("dishName").asText()).isEqualTo("Dal");
        assertThat(proposal.path("servings").asInt()).isEqualTo(2);
        assertThat(proposal.path("lines").get(0).path("quantity").asInt()).isEqualTo(100);
        assertThat(json(as(get("/api/core/pending"), k), 200)).hasSize(1);
    }

    @Test
    void aLinkThatCannotBeReadFailsTheJobWithAMessageAndQueuesNothing() throws Exception {
        Kitchen k = kitchen();
        JsonNode job = awaitJob(json(post("/api/ai/recipe-links").contentType(MediaType.APPLICATION_JSON).content("""
                {"url":"https://recipes.example/gone","householdId":%d,"userId":%d}"""
                .formatted(k.householdId(), k.userId())), 202));

        assertThat(job.path("status").asText()).isEqualTo("FAILED");
        assertThat(job.path("error").asText()).contains("could not be reached");
        assertThat(job.path("pendingChangeId").isNull()).isTrue();
        assertThat(json(as(get("/api/core/pending"), k), 200)).isEmpty();
    }

    @Test
    void badRequestsAreRefusedBeforeAnyJobIsCreated() throws Exception {
        MockMultipartFile notAnImage = new MockMultipartFile("image", "x.png", "image/png", "hello".getBytes());
        mvc.perform(multipart("/api/ai/receipts").file(notAnImage).param("householdId", "25").param("userId", "5"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("That file is not a JPEG or PNG image"));
        mvc.perform(multipart("/api/ai/receipts").file(image("image")).param("userId", "5"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(multipart("/api/ai/receipts").file(image("image")).param("householdId", "-1").param("userId", "5"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/ai/receipts").param("householdId", "25").param("userId", "5"))
                .andExpect(status().isBadRequest());

        MockHttpServletRequestBuilder sevenPhotos = multipart("/api/ai/photos");
        for (int i = 0; i < 7; i++) {
            sevenPhotos = ((org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder) sevenPhotos)
                    .file(image("images"));
        }
        mvc.perform(sevenPhotos.param("householdId", "25").param("userId", "5")).andExpect(status().isBadRequest());

        for (String body : new String[] {
            "{\"url\":\"file:///etc/passwd\",\"householdId\":25,\"userId\":5}",
            "{\"url\":\"\",\"householdId\":25,\"userId\":5}",
            "{\"url\":\"https://recipes.example/dal\",\"servingsMade\":0,\"householdId\":25,\"userId\":5}",
            "{\"url\":\"https://recipes.example/dal\",\"userId\":5}",
            "{not json"
        }) {
            mvc.perform(post("/api/ai/recipe-links").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        }

        mvc.perform(get("/api/ai/jobs/99999999")).andExpect(status().isNotFound());
        assertThat(json(get("/api/ai/jobs").param("householdId", "25"), 200)).isEmpty();
    }

    @Test
    void priceIsEstimatedOnceCachedAndReplacedByTheUsersOwnPrice() throws Exception {
        llm.answer("{\"pricePerServing\":17.99}");

        JsonNode first = json(get("/api/ai/price-estimate")
                .param("dish", "Test Palak Paneer").param("servings", "2").param("city", "Testville"), 200);
        assertThat(first.path("pricePerServing").decimalValue()).isEqualByComparingTo("17.99");
        assertThat(first.path("total").decimalValue()).isEqualByComparingTo("35.98");
        assertThat(first.path("basis").asText()).isEqualTo("MODEL");
        assertThat(first.path("estimated").asBoolean()).isTrue();
        assertThat(first.path("currency").asText()).isEqualTo("USD");

        // same dish in different capitals: answered from the cache, the model is not asked again
        llm.answer("{\"pricePerServing\":99}");
        JsonNode second = json(get("/api/ai/price-estimate")
                .param("dish", "  test PALAK paneer ").param("servings", "1").param("city", "testville"), 200);
        assertThat(second.path("pricePerServing").decimalValue()).isEqualByComparingTo("17.99");
        assertThat(llm.requests()).hasSize(1);

        JsonNode edited = json(put("/api/ai/price-estimate").contentType(MediaType.APPLICATION_JSON).content("""
                {"dish":"Test Palak Paneer","city":"Testville","pricePerServing":15.5}"""), 200);
        assertThat(edited.path("basis").asText()).isEqualTo("USER_EDIT");
        assertThat(edited.path("estimated").asBoolean()).isFalse();

        JsonNode third = json(get("/api/ai/price-estimate")
                .param("dish", "Test Palak Paneer").param("servings", "3").param("city", "Testville"), 200);
        assertThat(third.path("pricePerServing").decimalValue()).isEqualByComparingTo("15.50");
        assertThat(third.path("total").decimalValue()).isEqualByComparingTo("46.50");
        assertThat(third.path("estimated").asBoolean()).isFalse();

        // the insights placeholder reaches the estimator through the shared interface
        JsonNode saved = json(get("/api/stub/insights/money-saved").param("dish", "Test Palak Paneer")
                .param("servings", "2").param("city", "Testville").param("ingredientCost", "7.97"), 200);
        assertThat(saved.path("saved").decimalValue()).isEqualByComparingTo("35.08");
    }

    @Test
    void priceRequestsThatCannotBeAnsweredGetAClearStatus() throws Exception {
        mvc.perform(get("/api/ai/price-estimate").param("dish", " ")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/ai/price-estimate").param("dish", "Test Soup").param("servings", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/ai/price-estimate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dish\":\"Test Soup\",\"pricePerServing\":-3}"))
                .andExpect(status().isBadRequest());

        llm.unavailable();
        mvc.perform(get("/api/ai/price-estimate").param("dish", "Test Unknown Dish"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").exists());

        llm.answer("{\"pricePerServing\":100000}");
        mvc.perform(get("/api/ai/price-estimate").param("dish", "Test Gold Leaf Steak"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void statusReportsWhatIsAvailable() throws Exception {
        JsonNode status = json(get("/api/ai/status"), 200);
        assertThat(status.path("modelServer").asBoolean()).isTrue();
        assertThat(status.path("ocr").asBoolean()).isTrue();
        assertThat(status.path("videoLinks").asBoolean()).isFalse();
        assertThat(status.path("webPriceSearch").asBoolean()).isFalse();
    }
}
