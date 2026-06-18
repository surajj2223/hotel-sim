package com.hotelops.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.core.TestcontainersConfiguration;
import com.hotelops.core.booking.BookingService;
import com.hotelops.core.common.auth.HumanAuthorizationGate;
import com.hotelops.core.payment.psp.PspGateway;
import com.hotelops.core.payment.psp.dto.PspPaymentLinkResponse;
import com.hotelops.core.product.ProductService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Folio completion lifecycle over real HTTP — API-014 completeLine, API-015 completeFolio.
 *
 * Asserts: line ACTIVE→COMPLETED (ungated) and CANCELLED-line rejection; folio
 * CONFIRMED→COMPLETED happy path; C1 (straggler ACTIVE line) and C2 (customerOwes != 0)
 * hard-fails with the live state echoed; refunded-but-settled completes (RX-003 §5);
 * idempotent re-complete (200) vs terminal CANCELLED (409); INV-007 428 without X-Human-Auth.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FolioCompletionApiTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping HTTP folio-completion test: no container runtime available.");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService productService;
    @Autowired BookingService bookingService;

    /** Outbound PSP call is not under test; stub link minting (the column is UNIQUE). */
    @MockitoBean PspGateway pspGateway;

    @BeforeEach
    void stubPsp() {
        when(pspGateway.createLink(any())).thenAnswer(inv -> new PspPaymentLinkResponse(
                "PL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "PENDING"));
    }

    private static final String HA = HumanAuthorizationGate.HEADER_NAME;
    private static final String HA_OK = "human-confirmed-yes";
    private static final String STARTS_AT = "2026-08-01T15:00:00Z";
    private static final String ENDS_AT   = "2026-08-02T11:00:00Z";   // 1 night → lineAmount == rate
    private static final long PRICE = 60_000L;                        // £600 room

    // ── API-014 completeLine ──────────────────────────────────────────────────

    @Test
    void API_014_completeLine_marksLineCompleted_noFolioSideEffect() throws Exception {
        UUID bookingId = bookingWithRoomLine();
        UUID lineId = firstLineId(bookingId);

        // Ungated: no X-Human-Auth header. Returns the folio with the line COMPLETED and the
        // booking status UNCHANGED (completion never flips the booking — T-A).
        mvc.perform(post("/bookings/" + bookingId + "/lines/" + lineId + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.lines[0].status").value("COMPLETED"));
    }

    @Test
    void API_014_completeLine_409_onCancelledLine() throws Exception {
        UUID bookingId = bookingWithRoomLine();
        UUID lineId = firstLineId(bookingId);
        bookingService.cancelLine(lineId);   // CANCELLED is terminal

        mvc.perform(post("/bookings/" + bookingId + "/lines/" + lineId + "/complete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void API_014_completeLine_404_whenLineNotInBooking() throws Exception {
        UUID bookingId = bookingWithRoomLine();

        mvc.perform(post("/bookings/" + bookingId + "/lines/" + UUID.randomUUID() + "/complete"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── API-015 completeFolio ─────────────────────────────────────────────────

    @Test
    void API_015_completeFolio_happyPath_allLinesCompleted_andSettled() throws Exception {
        UUID bookingId = bookingWithRoomLine();
        UUID lineId = firstLineId(bookingId);
        payInFull(bookingId, PRICE);          // pay BEFORE completing (totals roll up on ACTIVE lines)
        completeLine(bookingId, lineId);

        mvc.perform(post("/bookings/" + bookingId + "/complete").header(HA, HA_OK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.customerOwes").value(0))
                .andExpect(jsonPath("$.lines[0].status").value("COMPLETED"));
    }

    @Test
    void API_015_completeFolio_409_C1_stragglerActiveLine() throws Exception {
        UUID bookingId = bookingWithRoomLine();
        UUID lineId = firstLineId(bookingId);
        payInFull(bookingId, PRICE);          // C2 satisfied; isolate C1 (line still ACTIVE)

        mvc.perform(post("/bookings/" + bookingId + "/complete").header(HA, HA_OK))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.currentState.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.currentState.customerOwes").value(0))
                .andExpect(jsonPath("$.currentState.incompleteLineIds", hasItem(lineId.toString())));

        // No write occurred — folio stays CONFIRMED.
        mvc.perform(get("/bookings/" + bookingId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void API_015_completeFolio_409_C2_customerOwes() throws Exception {
        UUID bookingId = bookingWithRoomLine();
        UUID lineId = firstLineId(bookingId);
        completeLine(bookingId, lineId);      // C1 satisfied; unpaid so C2 fails

        mvc.perform(post("/bookings/" + bookingId + "/complete").header(HA, HA_OK))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.currentState.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.currentState.customerOwes").value((int) PRICE))
                .andExpect(jsonPath("$.currentState.incompleteLineIds.length()").value(0));

        mvc.perform(get("/bookings/" + bookingId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void API_015_completeFolio_refundedButSettled_completes() throws Exception {
        // RX-003 §5: pay £600, refund £100 → customerOwes 0 (refund does not reopen a debt),
        // netRevenue 500. With the line COMPLETED the folio closes cleanly.
        UUID bookingId = bookingWithRoomLine();
        UUID lineId = firstLineId(bookingId);
        Paid paid = payInFull(bookingId, PRICE);
        refund(paid, 10_000L);                // refund BEFORE completing (recalc runs on ACTIVE line)
        completeLine(bookingId, lineId);

        mvc.perform(post("/bookings/" + bookingId + "/complete").header(HA, HA_OK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.customerOwes").value(0))
                .andExpect(jsonPath("$.netRevenue").value(50_000));
    }

    @Test
    void API_015_completeFolio_idempotent_onAlreadyCompleted() throws Exception {
        UUID bookingId = bookingWithRoomLine();
        UUID lineId = firstLineId(bookingId);
        payInFull(bookingId, PRICE);
        completeLine(bookingId, lineId);

        mvc.perform(post("/bookings/" + bookingId + "/complete").header(HA, HA_OK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        // Second call on a COMPLETED folio → idempotent 200, same state (Q2).
        mvc.perform(post("/bookings/" + bookingId + "/complete").header(HA, HA_OK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void API_015_completeFolio_409_onCancelledBooking() throws Exception {
        UUID bookingId = bookingWithRoomLine();
        UUID lineId = firstLineId(bookingId);
        bookingService.cancelLine(lineId);    // only line cancelled → booking CANCELLED (terminal)

        mvc.perform(post("/bookings/" + bookingId + "/complete").header(HA, HA_OK))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.currentState.status").value("CANCELLED"));
    }

    @Test
    void API_015_completeFolio_428_withoutHumanAuth() throws Exception {
        UUID bookingId = bookingWithRoomLine();

        mvc.perform(post("/bookings/" + bookingId + "/complete"))   // no X-Human-Auth
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("completeFolio")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID bookingWithRoomLine() throws Exception {
        UUID customerId = createCustomer();
        UUID productId = productService.createRoom(
                "Completion Room " + UUID.randomUUID(), PRICE, "GBP",
                "HIGH", "KING", 2, true, 5).getId();
        UUID bookingId = createBooking(customerId);
        addLine(bookingId, productId);
        return bookingId;
    }

    private UUID createCustomer() throws Exception {
        return UUID.fromString(node(mvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Completion Test\"}"))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText());
    }

    private UUID createBooking(UUID customerId) throws Exception {
        return UUID.fromString(node(mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText());
    }

    private void addLine(UUID bookingId, UUID productId) throws Exception {
        mvc.perform(post("/bookings/" + bookingId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"startsAt\":\"" + STARTS_AT
                                + "\",\"endsAt\":\"" + ENDS_AT + "\",\"quantity\":1}"))
                .andExpect(status().isCreated());
    }

    private UUID firstLineId(UUID bookingId) throws Exception {
        return UUID.fromString(node(mvc.perform(get("/bookings/" + bookingId))
                .andExpect(status().isOk())
                .andReturn()).get("lines").get(0).get("id").asText());
    }

    private void completeLine(UUID bookingId, UUID lineId) throws Exception {
        mvc.perform(post("/bookings/" + bookingId + "/lines/" + lineId + "/complete"))
                .andExpect(status().isOk());
    }

    private record Paid(UUID paymentId, String mref, String authPspRef) {}

    /** Create a payment for the full amount and settle it (AUTHORISATION + CAPTURE). */
    private Paid payInFull(UUID bookingId, long amount) throws Exception {
        UUID paymentId = UUID.fromString(node(mvc.perform(post("/bookings/" + bookingId + "/payments")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText());
        String mref = node(mvc.perform(get("/payments/" + paymentId))
                .andExpect(status().isOk()).andReturn()).get("merchantReference").asText();
        String authPspRef = newPspRef();
        deliverEvent("AUTHORISATION", authPspRef + ":AUTHORISATION:1", mref, authPspRef, amount,
                ",\"authExpiresAt\":\"2026-08-04T11:00:00Z\"");
        String capPspRef = newPspRef();
        deliverEvent("CAPTURE", capPspRef + ":CAPTURE:1", mref, capPspRef, amount, "");
        return new Paid(paymentId, mref, authPspRef);
    }

    private void refund(Paid paid, long amount) throws Exception {
        String refundMref = node(mvc.perform(post("/payments/" + paid.paymentId() + "/refunds")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + ",\"reason\":\"Goodwill\"}"))
                .andExpect(status().isAccepted())
                .andReturn()).get("merchantReference").asText();
        String refundPspRef = newPspRef();
        deliverEvent("REFUND", refundPspRef + ":REFUND:1", paid.mref(), refundPspRef, amount,
                ",\"refundMerchantReference\":\"" + refundMref
                        + "\",\"originalReference\":\"" + paid.authPspRef() + "\"");
    }

    private static String newPspRef() {
        return "PSP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void deliverEvent(String code, String idKey, String mref, String pspRef,
                              long amount, String extra) throws Exception {
        mvc.perform(post("/webhooks/psp")
                        .header("X-PSP-Signature", "test-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody(code, idKey, mref, pspRef, amount, extra)))
                .andExpect(status().isOk());
    }

    private String eventBody(String code, String idKey, String mref, String pspRef,
                             long amount, String extra) {
        return "{"
                + "\"eventId\":\"evt-" + UUID.randomUUID() + "\","
                + "\"eventCode\":\"" + code + "\","
                + "\"idempotencyKey\":\"" + idKey + "\","
                + "\"merchantReference\":\"" + mref + "\","
                + "\"pspReference\":\"" + pspRef + "\","
                + "\"amount\":" + amount + ","
                + "\"currency\":\"GBP\","
                + "\"occurredAt\":\"2026-08-01T15:00:00Z\","
                + "\"success\":true"
                + extra
                + "}";
    }

    private JsonNode node(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
