package com.hotelops.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.core.TestcontainersConfiguration;
import com.hotelops.core.common.auth.HumanAuthorizationGate;
import com.hotelops.core.common.enums.PostingType;
import com.hotelops.core.ledger.LedgerPosting;
import com.hotelops.core.ledger.LedgerPostingRepository;
import com.hotelops.core.ledger.OutboxProcessor;
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

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-016 getRevenue + API-017 listUnpaidBookings over real HTTP (MockMvc + Testcontainers).
 *
 * Proves, against the FROZEN contract (WAVE0_02_OPENAPI.yaml):
 *   - revenue: gross / refundedTotal / net identity per vertical + totals roll-up, with a partial
 *     refund (REFUND_REVERSAL stored negative → refundedTotal is its absolute value);
 *   - revenue: the window is half-open [from, to) on posting time — a posting exactly at {@code to}
 *     is excluded, the same posting at {@code from} is included;
 *   - unpaid: a held-auth-only line shows lineOwes == lineAmount and lineHeldAuth == the auth
 *     (a held authorisation does NOT reduce owes), while a captured line shows lineOwes == 0 and
 *     lineHeldAuth == 0 (the held-auth sum is AUTHORISED-parent only).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReportingApiTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping reporting test: no container runtime available.");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService productService;
    @Autowired OutboxProcessor outboxProcessor;
    @Autowired LedgerPostingRepository ledgerPostingRepository;

    @MockitoBean PspGateway pspGateway;

    @BeforeEach
    void stubPsp() {
        when(pspGateway.createLink(any())).thenAnswer(inv -> new PspPaymentLinkResponse(
                "PL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "PENDING"));
    }

    private static final String HA = HumanAuthorizationGate.HEADER_NAME;
    private static final String HA_OK = "human-confirmed-yes";
    private static final String STARTS_AT = "2027-05-01T15:00:00Z";
    private static final String ENDS_AT   = "2027-05-02T11:00:00Z";   // 1 night → room lineAmount == ROOM_PRICE
    private static final long   ROOM_PRICE = 180_000L;
    private static final long   SPA_PRICE  =  20_000L;
    private static final long   REFUND     =  30_000L;

    // ── API-016: gross / refundedTotal / net per vertical + totals, with a partial refund ──

    @Test
    void getRevenue_perVertical_grossRefundedNetIdentity_andTotals() throws Exception {
        UUID roomId = productService.createRoom(
                "Rev Room " + UUID.randomUUID(), ROOM_PRICE, "GBP", "HIGH", "KING", 2, true, 5).getId();
        UUID spaId = productService.createSpa(
                "Rev Spa " + UUID.randomUUID(), SPA_PRICE, "GBP", "MASSAGE_60", 60, null, 3).getId();

        // Drain any backlog from other tests FIRST: processPending stamps postedAt = now(), so
        // unprocessed events from sibling classes would otherwise land inside this method's window
        // (the outbox is shared across the cached Spring context). After this, the window below
        // brackets only this method's own postings.
        outboxProcessor.processPending();
        OffsetDateTime windowStart = OffsetDateTime.now();

        // ROOM booking: pay £1,800 in full, then partially refund £300.
        UUID roomBooking = createBooking(createCustomer());
        addLine(roomBooking, roomId);
        JsonNode roomPay = createLink(roomBooking, ROOM_PRICE, "MANUAL");
        UUID roomPaymentId = UUID.fromString(roomPay.get("id").asText());
        String roomMref = roomPay.get("merchantReference").asText();
        String roomAuthPsp = driveAuthorisation(roomMref, ROOM_PRICE);
        driveCapture(roomMref, ROOM_PRICE);
        outboxProcessor.processPending();
        refund(roomPaymentId, roomMref, roomAuthPsp, REFUND);
        outboxProcessor.processPending();

        // SPA booking: pay £200 in full (IMMEDIATE), no refund.
        UUID spaBooking = createBooking(createCustomer());
        addLine(spaBooking, spaId);
        String spaMref = createLink(spaBooking, SPA_PRICE, "IMMEDIATE").get("merchantReference").asText();
        driveAuthorisation(spaMref, SPA_PRICE);
        driveCapture(spaMref, SPA_PRICE);
        outboxProcessor.processPending();

        OffsetDateTime windowEnd = OffsetDateTime.now();

        JsonNode report = node(mvc.perform(get("/reports/revenue")
                        .param("from", windowStart.toString())
                        .param("to", windowEnd.toString()))
                .andExpect(status().isOk()).andReturn());

        JsonNode room = byVertical(report, "ROOM");
        assertThat(room.get("gross").asLong()).isEqualTo(ROOM_PRICE);
        assertThat(room.get("refundedTotal").asLong()).isEqualTo(REFUND);                 // |Σ reversal|, >= 0
        assertThat(room.get("net").asLong()).isEqualTo(ROOM_PRICE - REFUND);              // derived identity

        JsonNode spa = byVertical(report, "SPA");
        assertThat(spa.get("gross").asLong()).isEqualTo(SPA_PRICE);
        assertThat(spa.get("refundedTotal").asLong()).isEqualTo(0L);
        assertThat(spa.get("net").asLong()).isEqualTo(SPA_PRICE);

        JsonNode totals = report.get("totals");
        assertThat(totals.get("gross").asLong()).isEqualTo(ROOM_PRICE + SPA_PRICE);
        assertThat(totals.get("refundedTotal").asLong()).isEqualTo(REFUND);
        assertThat(totals.get("net").asLong()).isEqualTo(ROOM_PRICE + SPA_PRICE - REFUND);
        assertThat(report.get("currency").asText()).isEqualTo("GBP");
    }

    // ── API-016: half-open [from, to) — a posting exactly at `to` is excluded ──

    @Test
    void getRevenue_halfOpenWindow_postingAtToExcluded_atFromIncluded() throws Exception {
        UUID roomId = productService.createRoom(
                "Boundary Room " + UUID.randomUUID(), ROOM_PRICE, "GBP", "HIGH", "KING", 2, true, 5).getId();
        UUID booking = createBooking(createCustomer());
        addLine(booking, roomId);
        String mref = createLink(booking, ROOM_PRICE, "MANUAL").get("merchantReference").asText();
        driveAuthorisation(mref, ROOM_PRICE);
        driveCapture(mref, ROOM_PRICE);
        outboxProcessor.processPending();

        // The single REVENUE posting's exact posting time.
        OffsetDateTime postedAt = ledgerPostingRepository.findByBookingId(booking).stream()
                .filter(p -> p.getPostingType() == PostingType.REVENUE)
                .map(LedgerPosting::getPostedAt)
                .findFirst().orElseThrow();

        // [postedAt, postedAt+1µs): `from` is inclusive → posting counted.
        JsonNode incl = node(mvc.perform(get("/reports/revenue")
                        .param("from", postedAt.toString())
                        .param("to", postedAt.plusNanos(1_000).toString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(byVertical(incl, "ROOM").get("gross").asLong()).isEqualTo(ROOM_PRICE);

        // [postedAt-1µs, postedAt): `to` is exclusive → same posting NOT counted (ROOM absent).
        JsonNode excl = node(mvc.perform(get("/reports/revenue")
                        .param("from", postedAt.minusNanos(1_000).toString())
                        .param("to", postedAt.toString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(byVerticalOrNull(excl, "ROOM")).isNull();
    }

    // ── API-017: held auth does NOT reduce owes; captured line owes nothing ──

    @Test
    void listUnpaid_heldAuthLine_owesFullAmount_capturedLine_owesZero() throws Exception {
        UUID roomId = productService.createRoom(
                "Unpaid Room " + UUID.randomUUID(), ROOM_PRICE, "GBP", "HIGH", "KING", 2, true, 5).getId();
        UUID spaId = productService.createSpa(
                "Unpaid Spa " + UUID.randomUUID(), SPA_PRICE, "GBP", "MASSAGE_60", 60, null, 3).getId();

        UUID booking = createBooking(createCustomer());
        addLine(booking, roomId);
        addLine(booking, spaId);
        UUID roomLineId = lineIdByVertical(booking, "ROOM");
        UUID spaLineId  = lineIdByVertical(booking, "SPA");

        // Room: MANUAL auth ONLY (held, never captured) → payment stays AUTHORISED.
        String roomMref = createScopedLink(booking, ROOM_PRICE, "MANUAL",
                "{\"bookingLineId\":\"" + roomLineId + "\",\"amount\":" + ROOM_PRICE + "}")
                .get("merchantReference").asText();
        driveAuthorisation(roomMref, ROOM_PRICE);

        // Spa: IMMEDIATE auth + capture → payment CAPTURED, revenue posted.
        String spaMref = createScopedLink(booking, SPA_PRICE, "IMMEDIATE",
                "{\"bookingLineId\":\"" + spaLineId + "\",\"amount\":" + SPA_PRICE + "}")
                .get("merchantReference").asText();
        driveAuthorisation(spaMref, SPA_PRICE);
        driveCapture(spaMref, SPA_PRICE);
        outboxProcessor.processPending();

        // total = 200000, paid = 20000 → total > paid → booking is unpaid.
        JsonNode b = unpaidBooking(booking);
        assertThat(b.get("totalAmount").asLong()).isEqualTo(ROOM_PRICE + SPA_PRICE);
        assertThat(b.get("amountPaid").asLong()).isEqualTo(SPA_PRICE);
        assertThat(b.get("customerOwes").asLong()).isEqualTo(ROOM_PRICE);

        JsonNode roomLine = line(b, roomLineId);
        assertThat(roomLine.get("lineRevenuePosted").asLong()).isEqualTo(0L);
        assertThat(roomLine.get("lineHeldAuth").asLong()).isEqualTo(ROOM_PRICE);          // held against the line
        assertThat(roomLine.get("lineOwes").asLong()).isEqualTo(ROOM_PRICE);              // held auth does NOT reduce owes

        JsonNode spaLine = line(b, spaLineId);
        assertThat(spaLine.get("lineRevenuePosted").asLong()).isEqualTo(SPA_PRICE);
        assertThat(spaLine.get("lineHeldAuth").asLong()).isEqualTo(0L);                   // parent CAPTURED, not AUTHORISED
        assertThat(spaLine.get("lineOwes").asLong()).isEqualTo(0L);                       // fully captured
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private JsonNode createLink(UUID bookingId, long amount, String captureMode) throws Exception {
        String body = "{\"amount\":" + amount + ",\"captureMode\":\"" + captureMode + "\"}";
        return node(mvc.perform(post("/bookings/" + bookingId + "/payments")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode createScopedLink(UUID bookingId, long amount, String captureMode,
                                      String coverageEntries) throws Exception {
        String body = "{\"amount\":" + amount + ",\"captureMode\":\"" + captureMode
                + "\",\"lineCoverage\":[" + coverageEntries + "]}";
        return node(mvc.perform(post("/bookings/" + bookingId + "/payments")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn());
    }

    private void refund(UUID paymentId, String mref, String originalPspRef, long amount) throws Exception {
        String refundMref = node(mvc.perform(post("/payments/" + paymentId + "/refunds")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + ",\"reason\":\"Goodwill\"}"))
                .andExpect(status().isAccepted()).andReturn()).get("merchantReference").asText();
        String refundPsp = newPspRef();
        deliverEvent("REFUND", refundPsp + ":REFUND:1", mref, refundPsp, amount,
                ",\"refundMerchantReference\":\"" + refundMref
                        + "\",\"originalReference\":\"" + originalPspRef + "\"");
    }

    /** Drives an AUTHORISATION webhook; returns its pspReference (the refund originalReference). */
    private String driveAuthorisation(String mref, long amount) throws Exception {
        String pspRef = newPspRef();
        deliverEvent("AUTHORISATION", pspRef + ":AUTHORISATION:1", mref, pspRef, amount,
                ",\"authExpiresAt\":\"2027-05-05T11:00:00Z\"");
        return pspRef;
    }

    private void driveCapture(String mref, long amount) throws Exception {
        String pspRef = newPspRef();
        deliverEvent("CAPTURE", pspRef + ":CAPTURE:1", mref, pspRef, amount, "");
    }

    private void deliverEvent(String code, String idKey, String mref, String pspRef,
                              long amount, String extra) throws Exception {
        String body = "{"
                + "\"eventId\":\"evt-" + UUID.randomUUID() + "\","
                + "\"eventCode\":\"" + code + "\","
                + "\"idempotencyKey\":\"" + idKey + "\","
                + "\"merchantReference\":\"" + mref + "\","
                + "\"pspReference\":\"" + pspRef + "\","
                + "\"amount\":" + amount + ","
                + "\"currency\":\"GBP\","
                + "\"occurredAt\":\"2027-05-01T15:30:00Z\","
                + "\"success\":true"
                + extra + "}";
        mvc.perform(post("/webhooks/psp")
                        .header("X-PSP-Signature", "test-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private static String newPspRef() {
        return "PSP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** byVertical row for a vertical, or fails if absent. */
    private JsonNode byVertical(JsonNode report, String vertical) {
        JsonNode row = byVerticalOrNull(report, vertical);
        if (row == null) {
            throw new AssertionError("No " + vertical + " row in revenue report");
        }
        return row;
    }

    private JsonNode byVerticalOrNull(JsonNode report, String vertical) {
        for (JsonNode row : report.get("byVertical")) {
            if (vertical.equals(row.get("vertical").asText())) {
                return row;
            }
        }
        return null;
    }

    private JsonNode unpaidBooking(UUID bookingId) throws Exception {
        JsonNode report = node(mvc.perform(get("/reports/unpaid-bookings"))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode bk : report.get("bookings")) {
            if (bookingId.toString().equals(bk.get("bookingId").asText())) {
                return bk;
            }
        }
        throw new AssertionError("Booking " + bookingId + " not in unpaid worklist");
    }

    private JsonNode line(JsonNode booking, UUID lineId) {
        for (JsonNode l : booking.get("lines")) {
            if (lineId.toString().equals(l.get("lineId").asText())) {
                return l;
            }
        }
        throw new AssertionError("No line " + lineId + " on unpaid booking");
    }

    private UUID lineIdByVertical(UUID bookingId, String vertical) throws Exception {
        JsonNode folio = node(mvc.perform(get("/bookings/" + bookingId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode l : folio.get("lines")) {
            if (vertical.equals(l.get("vertical").asText())) {
                return UUID.fromString(l.get("id").asText());
            }
        }
        throw new AssertionError("No " + vertical + " line on booking " + bookingId);
    }

    private UUID createCustomer() throws Exception {
        return UUID.fromString(node(mvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Reporting Test\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
    }

    private UUID createBooking(UUID customerId) throws Exception {
        return UUID.fromString(node(mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
    }

    private void addLine(UUID bookingId, UUID productId) throws Exception {
        mvc.perform(post("/bookings/" + bookingId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"startsAt\":\""
                                + STARTS_AT + "\",\"endsAt\":\"" + ENDS_AT + "\",\"quantity\":1}"))
                .andExpect(status().isCreated());
    }

    private JsonNode node(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
