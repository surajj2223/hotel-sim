package com.hotelops.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.core.TestcontainersConfiguration;
import com.hotelops.core.product.ProductFnb;
import com.hotelops.core.product.ProductRoom;
import com.hotelops.core.product.ProductService;
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
 * API-004 / ENM-001 — F&B (FNB) availability + booking over real HTTP (MockMvc + Testcontainers Postgres).
 *
 * Observability slice: FnbStrategy shipped (PR #33) and is already HTTP-reachable through the
 * generic AvailabilityController / BookingService (both dispatch via the strategy registry —
 * no F&B-specific endpoint exists or is wanted, charter §2). This test exercises the wired path
 * end-to-end:
 *   - GET /availability?vertical=FNB returns coversCapacity − committed overlap
 *   - booking an F&B line via the generic POST /bookings/{id}/lines drops availableUnits
 *   - the F&B line is priced base × quantity (no nights factor — duration pricing is Rooms-only)
 *   - the F&B availability row carries fnbAttributes populated from product_fnb (API-004 Slice A5),
 *     and NEITHER roomAttributes NOR spaAttributes (each vertical populates only its own block)
 *   - ROOM availability is unbroken (no regression) and ROOM rows carry no fnbAttributes
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FnbAvailabilityApiTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping FNB availability test: no container runtime available.");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService productService;

    private static final String STARTS_AT = "2026-07-10T19:00:00Z";
    private static final String ENDS_AT   = "2026-07-10T20:00:00Z";
    private static final long   FNB_PRICE = 4_500L;
    private static final int    FNB_COVERS = 40;
    private static final int    BOOKED_COVERS = 2;

    @Test
    void fnb_availability_reflects_committed_overlap() throws Exception {
        // 1. Seed FNB product: coversCapacity = 40, DINNER service period.
        ProductFnb fnb = productService.createFnb(
                "Dinner Service", FNB_PRICE, "GBP", "DINNER", FNB_COVERS, 120);
        UUID fnbId = fnb.getId();

        // 2. API-004 — no committed overlap: availableUnits == FNB_COVERS.
        JsonNode firstResults = array(mvc.perform(get("/availability")
                        .param("vertical", "FNB")
                        .param("startsAt", STARTS_AT)
                        .param("endsAt", ENDS_AT))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode fnbRow = findProduct(firstResults, fnbId);
        assertThat(fnbRow).as("FNB product appears in /availability?vertical=FNB").isNotNull();
        assertThat(fnbRow.get("vertical").asText()).isEqualTo("FNB");
        assertThat(fnbRow.get("unitPrice").asLong()).isEqualTo(FNB_PRICE);
        assertThat(fnbRow.get("currency").asText()).isEqualTo("GBP");
        assertThat(fnbRow.get("availableUnits").asInt()).isEqualTo(FNB_COVERS);

        // API-004 Slice A5 — fnbAttributes is populated from product_fnb for an FNB row.
        JsonNode fnbAttrs = fnbRow.path("fnbAttributes");
        assertThat(fnbAttrs.isObject()).as("FNB result carries fnbAttributes").isTrue();
        assertThat(fnbAttrs.get("servicePeriod").asText()).isEqualTo("DINNER");
        assertThat(fnbAttrs.get("seatingMinutes").asInt()).isEqualTo(120);
        assertThat(fnbAttrs.get("coversCapacity").asInt()).isEqualTo(FNB_COVERS);

        // Cross-vertical integrity — each vertical populates only its own block, so an FNB row
        // carries neither roomAttributes nor spaAttributes.
        JsonNode roomAttrs = fnbRow.path("roomAttributes");
        assertThat(roomAttrs.isNull() || roomAttrs.isMissingNode())
                .as("FNB result carries no roomAttributes").isTrue();
        JsonNode spaAttrs = fnbRow.path("spaAttributes");
        assertThat(spaAttrs.isNull() || spaAttrs.isMissingNode())
                .as("FNB result carries no spaAttributes").isTrue();

        // 3. Book covers via the generic path: customer → folio → FNB line (same window).
        UUID customerId = uuid(mvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Frank Diner\"}"))
                .andExpect(status().isCreated())
                .andReturn(), "id");

        UUID bookingId = uuid(mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn(), "id");

        JsonNode folio = json.readTree(mvc.perform(post("/bookings/" + bookingId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + fnbId + "\",\"startsAt\":\"" + STARTS_AT
                                 + "\",\"endsAt\":\"" + ENDS_AT + "\",\"quantity\":" + BOOKED_COVERS + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        // F&B line priced base × quantity — no nights factor (duration pricing is Rooms-only).
        JsonNode fnbLine = findLine(folio, fnbId);
        assertThat(fnbLine).as("FNB line present on the folio").isNotNull();
        assertThat(fnbLine.get("lineAmount").asLong())
                .as("FNB line is base × quantity, never × nights")
                .isEqualTo(FNB_PRICE * BOOKED_COVERS);

        // 4. Committed line — availableUnits must drop by the booked covers.
        JsonNode afterResults = array(mvc.perform(get("/availability")
                        .param("vertical", "FNB")
                        .param("startsAt", STARTS_AT)
                        .param("endsAt", ENDS_AT))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode afterRow = findProduct(afterResults, fnbId);
        assertThat(afterRow).as("FNB product still appears after booking").isNotNull();
        assertThat(afterRow.get("availableUnits").asInt()).isEqualTo(FNB_COVERS - BOOKED_COVERS);

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
        assertThat(roomRow).as("ROOM product still reachable alongside FNB").isNotNull();
        assertThat(roomRow.get("availableUnits").asInt()).isEqualTo(1);

        // Cross-vertical null integrity (Slice A5) — fnbAttributes is null for a non-FNB (ROOM) row.
        JsonNode roomFnbAttrs = roomRow.path("fnbAttributes");
        assertThat(roomFnbAttrs.isNull() || roomFnbAttrs.isMissingNode())
                .as("ROOM result carries no fnbAttributes").isTrue();
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

    private JsonNode findLine(JsonNode folio, UUID productId) {
        for (JsonNode line : folio.path("lines")) {
            if (productId.toString().equals(line.get("productId").asText())) {
                return line;
            }
        }
        return null;
    }
}
