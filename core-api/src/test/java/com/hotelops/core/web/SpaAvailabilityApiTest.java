package com.hotelops.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.core.TestcontainersConfiguration;
import com.hotelops.core.product.ProductRoom;
import com.hotelops.core.product.ProductService;
import com.hotelops.core.product.ProductSpa;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-004 (Slice A3) — SPA availability over real HTTP (MockMvc + Testcontainers Postgres).
 *
 * Proves the strategy seam is vertical-agnostic:
 *   - GET /availability?vertical=SPA returns concurrentSlots − committed overlap
 *   - ROOM availability is unbroken (no regression)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SpaAvailabilityApiTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping SPA availability test: no container runtime available.");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService productService;

    private static final String STARTS_AT = "2026-07-10T10:00:00Z";
    private static final String ENDS_AT   = "2026-07-10T11:00:00Z";
    private static final long   SPA_PRICE = 9_500L;
    private static final int    SPA_SLOTS = 3;

    @Test
    void spa_availability_reflects_committed_overlap() throws Exception {
        // 1. Seed SPA product: concurrentSlots = 3.
        ProductSpa spa = productService.createSpa(
                "Swedish Massage 60", SPA_PRICE, "GBP", "MASSAGE_60", 60, null, SPA_SLOTS);
        UUID spaId = spa.getId();

        // 2. API-004 — no committed overlap: availableUnits == SPA_SLOTS.
        JsonNode firstResults = array(mvc.perform(get("/availability")
                        .param("vertical", "SPA")
                        .param("startsAt", STARTS_AT)
                        .param("endsAt", ENDS_AT))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode spaRow = findProduct(firstResults, spaId);
        assertThat(spaRow).as("SPA product appears in /availability?vertical=SPA").isNotNull();
        assertThat(spaRow.get("vertical").asText()).isEqualTo("SPA");
        assertThat(spaRow.get("unitPrice").asLong()).isEqualTo(SPA_PRICE);
        assertThat(spaRow.get("currency").asText()).isEqualTo("GBP");
        assertThat(spaRow.get("availableUnits").asInt()).isEqualTo(SPA_SLOTS);
        JsonNode roomAttrs = spaRow.path("roomAttributes");
        assertThat(roomAttrs.isNull() || roomAttrs.isMissingNode())
                .as("SPA result carries no roomAttributes").isTrue();

        // 3. Book 1 slot: customer → folio → SPA line (quantity 1, same window).
        UUID customerId = uuid(mvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Alice Spa\"}"))
                .andExpect(status().isCreated())
                .andReturn(), "id");

        UUID bookingId = uuid(mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn(), "id");

        mvc.perform(post("/bookings/" + bookingId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + spaId + "\",\"startsAt\":\"" + STARTS_AT
                                 + "\",\"endsAt\":\"" + ENDS_AT + "\",\"quantity\":1}"))
                .andExpect(status().isCreated());

        // 4. One active committed line — availableUnits must drop by 1.
        JsonNode afterResults = array(mvc.perform(get("/availability")
                        .param("vertical", "SPA")
                        .param("startsAt", STARTS_AT)
                        .param("endsAt", ENDS_AT))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode afterRow = findProduct(afterResults, spaId);
        assertThat(afterRow).as("SPA product still appears after booking").isNotNull();
        assertThat(afterRow.get("availableUnits").asInt()).isEqualTo(SPA_SLOTS - 1);

        // 5. ROOM regression — endpoint still returns 200 and the ROOM product.
        ProductRoom room = productService.createRoom(
                "Standard Room", 15_000L, "GBP", "LOW", "DOUBLE", 2, false, 1);
        JsonNode roomResults = array(mvc.perform(get("/availability")
                        .param("vertical", "ROOM")
                        .param("startsAt", STARTS_AT)
                        .param("endsAt", ENDS_AT))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode roomRow = findProduct(roomResults, room.getId());
        assertThat(roomRow).as("ROOM product still reachable after SPA widening").isNotNull();
        assertThat(roomRow.get("availableUnits").asInt()).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode array(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private UUID uuid(MvcResult result, String field) throws Exception {
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .get(field).asText());
    }

    private JsonNode findProduct(JsonNode array, UUID productId) {
        for (JsonNode item : array) {
            if (productId.toString().equals(item.get("productId").asText())) {
                return item;
            }
        }
        return null;
    }
}
