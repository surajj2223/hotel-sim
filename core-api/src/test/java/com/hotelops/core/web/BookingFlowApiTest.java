package com.hotelops.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.core.TestcontainersConfiguration;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-001..007 end-to-end over real HTTP (MockMvc) against a Testcontainers Postgres.
 *
 * Walks the Stage 1 "book a room" spine: createCustomer -> create room product (setup) ->
 * searchAvailability -> createBooking -> addLine -> getFolio, then asserts the INV-003
 * 409 path when a line exceeds availability.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BookingFlowApiTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping HTTP flow test: no container runtime available.");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService productService;

    private static final String STARTS_AT = "2026-07-01T15:00:00Z";
    private static final String ENDS_AT   = "2026-07-03T11:00:00Z";
    private static final long UNIT_PRICE  = 18_000L;   // per-night rate, pence
    private static final int ROOM_COUNT   = 2;
    // Jul 1 → Jul 3 = 2 calendar nights; room line debt = rate × quantity × nights
    // (KNOWN_LIMITATION_ROOM_PRICING.md). unitPrice stays the per-night rate.
    private static final int NIGHTS       = 2;
    private static final long LINE_AMOUNT = UNIT_PRICE * NIGHTS;   // 36_000

    @Test
    void book_a_room_end_to_end_plus_409_on_over_availability() throws Exception {
        // 1. API-001 — create customer; server mints shopperReference.
        MvcResult created = mvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"John Patel\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shopperReference", org.hamcrest.Matchers.startsWith("SHPR-")))
                .andReturn();
        UUID customerId = UUID.fromString(node(created).get("id").asText());

        // 2. Create a ROOM product (test setup uses the service directly).
        ProductRoom room = productService.createRoom(
                "Deluxe King", UNIT_PRICE, "GBP", "HIGH", "KING", 2, true, ROOM_COUNT);
        UUID productId = room.getId();

        // 3. API-004 — search availability; full room_count free, attributes present.
        JsonNode results = node(mvc.perform(get("/availability")
                        .param("vertical", "ROOM")
                        .param("startsAt", STARTS_AT)
                        .param("endsAt", ENDS_AT)
                        .param("quantity", "1"))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode row = findProduct(results, productId);
        assertThat(row).as("our product appears in availability").isNotNull();
        assertThat(row.get("vertical").asText()).isEqualTo("ROOM");
        assertThat(row.get("unitPrice").asLong()).isEqualTo(UNIT_PRICE);
        assertThat(row.get("currency").asText()).isEqualTo("GBP");
        assertThat(row.get("availableUnits").asInt()).isEqualTo(ROOM_COUNT);
        assertThat(row.get("roomAttributes").get("bedType").asText()).isEqualTo("KING");

        // 4. API-005 — open an empty folio (PENDING, no lines, zero amounts).
        MvcResult folioCreated = mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(0))
                .andExpect(jsonPath("$.customerOwes").value(0))
                .andExpect(jsonPath("$.netRevenue").value(0))
                .andExpect(jsonPath("$.lines.length()").value(0))
                .andReturn();
        UUID bookingId = UUID.fromString(node(folioCreated).get("id").asText());

        // 5. API-006 — add a room line (quantity 1). Folio confirms; totals recomputed.
        mvc.perform(post("/bookings/" + bookingId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineBody(productId, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalAmount").value(LINE_AMOUNT))
                .andExpect(jsonPath("$.customerOwes").value(LINE_AMOUNT))     // unpaid: owes == lineAmount
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].lineAmount").value(LINE_AMOUNT))   // rate × 2 nights
                .andExpect(jsonPath("$.lines[0].unitPrice").value(UNIT_PRICE))     // rate stays the per-night rate
                .andExpect(jsonPath("$.lines[0].quantity").value(1));

        // 6. API-007 — read folio back; availability dropped by the booked quantity.
        mvc.perform(get("/bookings/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.customerOwes").value(LINE_AMOUNT));

        JsonNode after = findProduct(node(mvc.perform(get("/availability")
                        .param("vertical", "ROOM")
                        .param("startsAt", STARTS_AT)
                        .param("endsAt", ENDS_AT))
                .andExpect(status().isOk())
                .andReturn()), productId);
        assertThat(after.get("availableUnits").asInt()).isEqualTo(ROOM_COUNT - 1);   // dropped by 1

        // 7. INV-003 — a line exceeding availability (needs 2, only 1 free) -> 409 StateConflict.
        mvc.perform(post("/bookings/" + bookingId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineBody(productId, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.currentState.availableUnits").value(ROOM_COUNT - 1));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String lineBody(UUID productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"startsAt\":\"" + STARTS_AT
                + "\",\"endsAt\":\"" + ENDS_AT + "\",\"quantity\":" + quantity + "}";
    }

    private JsonNode node(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
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
