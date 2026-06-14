package com.hotelops.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.core.TestcontainersConfiguration;
import com.hotelops.core.common.auth.HumanAuthorizationGate;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 4 Slice 2, Part 2 — the headline proof, now entirely over HTTP. A scoped £200 spa
 * payment created via the API-008 {@code lineCoverage} field credits the SPA line and leaves
 * the room line at £0, and the folio read exposes that through the derived per-line
 * {@code revenuePosted}. The service-layer equivalent lives in {@link ScopedAllocationApiTest}
 * (untouched); here the scoping, settlement (webhooks), and read all go through the HTTP seam.
 *
 * Contract references: WHK-016 (§5.1), WHK-007/012, INV-004, INV-006, D3, API-008 Slice S2.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ScopedRevenueHttpApiTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping scoped-revenue HTTP test: no container runtime available.");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService productService;
    @Autowired OutboxProcessor outboxProcessor;

    @MockitoBean PspGateway pspGateway;

    @BeforeEach
    void stubPsp() {
        when(pspGateway.createLink(any())).thenAnswer(inv -> new PspPaymentLinkResponse(
                "PL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "PENDING"));
    }

    private static final String HA = HumanAuthorizationGate.HEADER_NAME;
    private static final String HA_OK = "human-confirmed-yes";
    private static final String STARTS_AT = "2027-02-01T15:00:00Z";
    private static final String ENDS_AT   = "2027-02-03T11:00:00Z";
    private static final long ROOM_PRICE  = 180_000L;  // £1,800
    private static final long SPA_PRICE   =  20_000L;  // £200

    // ── Headline: scoped spa payment over HTTP → spa earns revenue, room earns none ──

    @Test
    void scopedSpaPayment_overHttp_creditsSpaLine_roomLineStaysZero() throws Exception {
        UUID roomId = productService.createRoom(
                "Rev Room " + UUID.randomUUID(), ROOM_PRICE, "GBP",
                "HIGH", "KING", 2, true, 5).getId();
        UUID spaId = productService.createSpa(
                "Rev Massage " + UUID.randomUUID(), SPA_PRICE, "GBP",
                "MASSAGE_60", 60, null, 3).getId();

        UUID bookingId = createBooking(createCustomer());

        // 1. Room line £1,800 → MANUAL auth scoped to the room line (over HTTP).
        addLine(bookingId, roomId);
        UUID roomLineId = lineIdByVertical(bookingId, "ROOM");
        String roomMref = createScopedLink(bookingId, ROOM_PRICE, "MANUAL",
                "{\"bookingLineId\":\"" + roomLineId + "\",\"amount\":180000}");
        driveAuthorisationWebhook(roomMref, ROOM_PRICE);

        assertFolio(bookingId, ROOM_PRICE, ROOM_PRICE, 0L);   // total/authorised 180000, paid 0
        assertLineRevenue(bookingId, "ROOM", 0L);             // authorised only — no revenue yet

        // 2. Add spa line £200 → total rises to £2,000, authorised stays £1,800 (the gap).
        addLine(bookingId, spaId);
        assertFolio(bookingId, ROOM_PRICE + SPA_PRICE, ROOM_PRICE, 0L);

        // 3. IMMEDIATE £200 scoped to the spa line (over HTTP) → AUTHORISATION → CAPTURE.
        UUID spaLineId = lineIdByVertical(bookingId, "SPA");
        String spaMref = createScopedLink(bookingId, SPA_PRICE, "IMMEDIATE",
                "{\"bookingLineId\":\"" + spaLineId + "\",\"amount\":20000}");
        driveAuthorisationWebhook(spaMref, SPA_PRICE);
        driveCaptureWebhook(spaMref, SPA_PRICE);
        outboxProcessor.processPending();

        // Headline: spa line earned £200, room line earned nothing — visible over HTTP.
        assertLineRevenue(bookingId, "SPA", SPA_PRICE);
        assertLineRevenue(bookingId, "ROOM", 0L);

        // Folio roll-ups: total 200000; spa auth+capture joins the secured roll-up (authorised
        // 200000) and pays £200 (matches the service-layer proof in ScopedAllocationApiTest).
        assertFolio(bookingId, ROOM_PRICE + SPA_PRICE, ROOM_PRICE + SPA_PRICE, SPA_PRICE);
    }

    // ── Multi-method: room on payment A, spa on payment B, each to its own vertical ──

    @Test
    void multiMethodScopedPayments_overHttp_eachLineShowsOwnRevenue() throws Exception {
        UUID roomId = productService.createRoom(
                "MMRev Room " + UUID.randomUUID(), ROOM_PRICE, "GBP",
                "HIGH", "KING", 2, true, 5).getId();
        UUID spaId = productService.createSpa(
                "MMRev Massage " + UUID.randomUUID(), SPA_PRICE, "GBP",
                "MASSAGE_60", 60, null, 3).getId();

        UUID bookingId = createBooking(createCustomer());
        addLine(bookingId, roomId);
        addLine(bookingId, spaId);
        UUID roomLineId = lineIdByVertical(bookingId, "ROOM");
        UUID spaLineId  = lineIdByVertical(bookingId, "SPA");

        // Payment A → room, MANUAL auth+capture £1,800.
        String mrefA = createScopedLink(bookingId, ROOM_PRICE, "MANUAL",
                "{\"bookingLineId\":\"" + roomLineId + "\",\"amount\":180000}");
        driveAuthorisationWebhook(mrefA, ROOM_PRICE);
        String pspA = driveCaptureWebhook(mrefA, ROOM_PRICE);

        // Payment B → spa, IMMEDIATE auth+capture £200.
        String mrefB = createScopedLink(bookingId, SPA_PRICE, "IMMEDIATE",
                "{\"bookingLineId\":\"" + spaLineId + "\",\"amount\":20000}");
        driveAuthorisationWebhook(mrefB, SPA_PRICE);
        String pspB = driveCaptureWebhook(mrefB, SPA_PRICE);

        outboxProcessor.processPending();

        // Each line's revenuePosted matches its own vertical, over HTTP, via distinct payments.
        assertLineRevenue(bookingId, "ROOM", ROOM_PRICE);
        assertLineRevenue(bookingId, "SPA", SPA_PRICE);
        assertThat(pspA).isNotEqualTo(pspB);

        // Folio fully authorised and fully paid (£2,000).
        assertFolio(bookingId, ROOM_PRICE + SPA_PRICE, ROOM_PRICE + SPA_PRICE, ROOM_PRICE + SPA_PRICE);
    }

    // ── Negative: coverage summing != amount → 400 (D4 surfaces over HTTP) ──

    @Test
    void scopedCoverageNotSummingToAmount_overHttp_returns400() throws Exception {
        UUID roomId = productService.createRoom(
                "BadRev Room " + UUID.randomUUID(), ROOM_PRICE, "GBP",
                "HIGH", "KING", 2, true, 5).getId();
        UUID spaId = productService.createSpa(
                "BadRev Massage " + UUID.randomUUID(), SPA_PRICE, "GBP",
                "MASSAGE_60", 60, null, 3).getId();
        UUID bookingId = createBooking(createCustomer());
        addLine(bookingId, roomId);
        addLine(bookingId, spaId);
        UUID roomLineId = lineIdByVertical(bookingId, "ROOM");
        UUID spaLineId  = lineIdByVertical(bookingId, "SPA");

        // amount 200000 but coverage sums to 190000 → persistCoverage rejects (400).
        String body = "{\"amount\":200000,\"captureMode\":\"MANUAL\",\"lineCoverage\":["
                + "{\"bookingLineId\":\"" + roomLineId + "\",\"amount\":180000},"
                + "{\"bookingLineId\":\"" + spaLineId + "\",\"amount\":10000}]}";

        mvc.perform(post("/bookings/" + bookingId + "/payments")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** POST a scoped create-link over HTTP; returns the server-minted merchantReference. */
    private String createScopedLink(UUID bookingId, long amount, String captureMode,
                                    String coverageEntries) throws Exception {
        String body = "{\"amount\":" + amount + ",\"captureMode\":\"" + captureMode
                + "\",\"lineCoverage\":[" + coverageEntries + "]}";
        return node(mvc.perform(post("/bookings/" + bookingId + "/payments")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()).get("merchantReference").asText();
    }

    private void assertFolio(UUID bookingId, long total, long authorised, long paid) throws Exception {
        mvc.perform(get("/bookings/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(total))
                .andExpect(jsonPath("$.amountAuthorised").value(authorised))
                .andExpect(jsonPath("$.amountPaid").value(paid))
                .andExpect(jsonPath("$.balance").value(total - paid));
    }

    private void assertLineRevenue(UUID bookingId, String vertical, long expected) throws Exception {
        JsonNode folio = node(mvc.perform(get("/bookings/" + bookingId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode line : folio.get("lines")) {
            if (vertical.equals(line.get("vertical").asText())) {
                assertThat(line.get("revenuePosted").asLong())
                        .as("%s line revenuePosted", vertical)
                        .isEqualTo(expected);
                return;
            }
        }
        throw new AssertionError("No " + vertical + " line on booking " + bookingId);
    }

    private UUID lineIdByVertical(UUID bookingId, String vertical) throws Exception {
        JsonNode folio = node(mvc.perform(get("/bookings/" + bookingId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode line : folio.get("lines")) {
            if (vertical.equals(line.get("vertical").asText())) {
                return UUID.fromString(line.get("id").asText());
            }
        }
        throw new AssertionError("No " + vertical + " line on booking " + bookingId);
    }

    private UUID createCustomer() throws Exception {
        return UUID.fromString(node(mvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Scoped Revenue Test\"}"))
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
                        .content("{\"productId\":\"" + productId + "\",\"startsAt\":\""
                                + STARTS_AT + "\",\"endsAt\":\"" + ENDS_AT
                                + "\",\"quantity\":1}"))
                .andExpect(status().isCreated());
    }

    private void driveAuthorisationWebhook(String mref, long amount) throws Exception {
        String pspRef = newPspRef();
        mvc.perform(post("/webhooks/psp")
                        .header("X-PSP-Signature", "test-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("AUTHORISATION", pspRef + ":AUTHORISATION:1", mref, pspRef,
                                amount, ",\"authExpiresAt\":\"2027-02-04T11:00:00Z\"")))
                .andExpect(status().isOk());
    }

    /** Drives a CAPTURE webhook and returns the capture's pspReference. */
    private String driveCaptureWebhook(String mref, long amount) throws Exception {
        String pspRef = newPspRef();
        mvc.perform(post("/webhooks/psp")
                        .header("X-PSP-Signature", "test-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("CAPTURE", pspRef + ":CAPTURE:1", mref, pspRef, amount, "")))
                .andExpect(status().isOk());
        return pspRef;
    }

    private static String newPspRef() {
        return "PSP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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
                + "\"occurredAt\":\"2027-02-01T15:30:00Z\","
                + "\"success\":true"
                + extra
                + "}";
    }

    private JsonNode node(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
