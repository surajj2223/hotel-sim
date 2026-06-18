package com.hotelops.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.core.TestcontainersConfiguration;
import com.hotelops.core.common.auth.HumanAuthorizationGate;
import com.hotelops.core.payment.psp.PspGateway;
import com.hotelops.core.payment.psp.dto.PspPaymentLinkResponse;
import com.hotelops.core.product.ProductRoom;
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
 * Operator-facing payment endpoints over real HTTP — API-008..012 (WAVE0_02).
 *
 * Asserts: 201/202 contracts; 428 envelope on missing X-Human-Auth (INV-007);
 * 404 / 409 paths (SCH-032 over-authorised; INV-005 single-capture; SCH-033 over-remaining);
 * 202-without-mutation under WHK-015 (state lands on the webhook, not on the 202).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentApiTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping HTTP payment test: no container runtime available.");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService productService;

    /**
     * The outbound PSP call is not under test here — these are the operator-facing HTTP
     * contracts. Stub PSP-001 to mint a unique {@code paymentLinkId} per call (the column is
     * UNIQUE and these tests persist across methods); capture/cancel/refund are void and
     * default to no-op. The real outbound seam is proven by {@code PspOutboundIntegrationTest}.
     */
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
    private static final long UNIT_PRICE  = 18_000L;

    // ── API-008 createPaymentLink ────────────────────────────────────────────

    @Test
    void API_008_createPaymentLink_201_mintsMerchantReference_andDefaultsCaptureModeFromStrategy() throws Exception {
        Ids ids = newBookingWithRoomLine();

        MvcResult result = mvc.perform(post("/bookings/" + ids.bookingId + "/payments")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":18000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.bookingId").value(ids.bookingId.toString()))
                .andExpect(jsonPath("$.amountRequested").value(UNIT_PRICE))
                .andExpect(jsonPath("$.amountAuthorised").value(0))
                .andExpect(jsonPath("$.amountCaptured").value(0))
                .andExpect(jsonPath("$.captureMode").value("MANUAL"))   // RoomStrategy default
                .andExpect(jsonPath("$.merchantReference",
                        org.hamcrest.Matchers.startsWith("MR-")))
                // Feature 2: PSP-001 mints the paymentLinkId, stamped after the outbound call.
                .andExpect(jsonPath("$.paymentLinkId",
                        org.hamcrest.Matchers.startsWith("PL-")))
                .andExpect(jsonPath("$.pspReference").doesNotExist())    // not until AUTHORISATION webhook
                .andExpect(jsonPath("$.refunds.length()").value(0))
                .andReturn();

        JsonNode body = node(result);
        assertThat(body.get("merchantReference").asText()).matches("^MR-[A-Za-z0-9]{32}$");
    }

    @Test
    void API_008_createPaymentLink_428_withoutHumanAuth_returnsApiErrorEnvelope() throws Exception {
        Ids ids = newBookingWithRoomLine();

        mvc.perform(post("/bookings/" + ids.bookingId + "/payments")
                        // no X-Human-Auth header
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":18000}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("createPaymentLink")));
    }

    @Test
    void API_008_createPaymentLink_404_unknownBooking() throws Exception {
        mvc.perform(post("/bookings/" + UUID.randomUUID() + "/payments")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":18000}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── API-009 getPayment / listBookingPayments (ungated) ───────────────────

    @Test
    void API_009_getPayment_200_andListBookingPayments_areUngated() throws Exception {
        Ids ids = newBookingWithRoomLine();
        UUID paymentId = createPayment(ids.bookingId);

        // getPayment — no X-Human-Auth required
        mvc.perform(get("/payments/" + paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // listBookingPayments — no X-Human-Auth required
        mvc.perform(get("/bookings/" + ids.bookingId + "/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(paymentId.toString()));
    }

    @Test
    void API_009_getPayment_404() throws Exception {
        mvc.perform(get("/payments/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── API-010 capturePayment ───────────────────────────────────────────────

    @Test
    void API_010_capturePayment_202_doesNotMutateUntilWebhook() throws Exception {
        Ids ids = newBookingWithRoomLine();
        UUID paymentId = createPayment(ids.bookingId);
        driveAuthorisationWebhook(paymentReference(paymentId), 18000L);

        // WHK-015: request → 202; final state lands on the webhook, not here.
        mvc.perform(post("/payments/" + paymentId + "/capture")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":18000}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("AUTHORISED"))   // not yet CAPTURED
                .andExpect(jsonPath("$.amountCaptured").value(0));
    }

    @Test
    void API_010_capturePayment_428_withoutHumanAuth() throws Exception {
        Ids ids = newBookingWithRoomLine();
        UUID paymentId = createPayment(ids.bookingId);
        driveAuthorisationWebhook(paymentReference(paymentId), 18000L);

        mvc.perform(post("/payments/" + paymentId + "/capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"));
    }

    @Test
    void API_010_capturePayment_409_amountOverAuthorised() throws Exception {
        Ids ids = newBookingWithRoomLine();
        UUID paymentId = createPayment(ids.bookingId);
        driveAuthorisationWebhook(paymentReference(paymentId), 18000L);

        mvc.perform(post("/payments/" + paymentId + "/capture")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50000}"))   // > authorised
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void API_010_capturePayment_409_secondCaptureRejected_INV_005() throws Exception {
        Ids ids = newBookingWithRoomLine();
        UUID paymentId = createPayment(ids.bookingId);
        String mref = paymentReference(paymentId);
        driveAuthorisationWebhook(mref, 18000L);
        driveCaptureWebhook(mref, 18000L);   // payment is now CAPTURED

        // INV-005: second capture must be rejected at the request side.
        mvc.perform(post("/payments/" + paymentId + "/capture")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("INV-005")));
    }

    // ── API-011 cancelAuthorisation ──────────────────────────────────────────

    @Test
    void API_011_cancelAuthorisation_202() throws Exception {
        Ids ids = newBookingWithRoomLine();
        UUID paymentId = createPayment(ids.bookingId);
        driveAuthorisationWebhook(paymentReference(paymentId), 18000L);

        mvc.perform(post("/payments/" + paymentId + "/cancel")
                        .header(HA, HA_OK))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("AUTHORISED"));    // flips on webhook
    }

    @Test
    void API_011_cancelAuthorisation_409_afterCapture() throws Exception {
        Ids ids = newBookingWithRoomLine();
        UUID paymentId = createPayment(ids.bookingId);
        String mref = paymentReference(paymentId);
        driveAuthorisationWebhook(mref, 18000L);
        driveCaptureWebhook(mref, 18000L);

        mvc.perform(post("/payments/" + paymentId + "/cancel")
                        .header(HA, HA_OK))
                .andExpect(status().isConflict());
    }

    @Test
    void API_011_cancelAuthorisation_428_withoutHumanAuth() throws Exception {
        Ids ids = newBookingWithRoomLine();
        UUID paymentId = createPayment(ids.bookingId);

        mvc.perform(post("/payments/" + paymentId + "/cancel"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"));
    }

    // ── API-012 refundPayment ────────────────────────────────────────────────

    @Test
    void API_012_refundPayment_202_withOriginalReferenceChain() throws Exception {
        Ids ids = newBookingWithRoomLine();
        UUID paymentId = createPayment(ids.bookingId);
        String mref = paymentReference(paymentId);
        driveAuthorisationWebhook(mref, 18000L);
        driveCaptureWebhook(mref, 18000L);

        mvc.perform(post("/payments/" + paymentId + "/refunds")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":5000,\"reason\":\"Goodwill\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.amount").value(5000))
                .andExpect(jsonPath("$.status").value("PENDING"))    // flips on REFUND webhook
                .andExpect(jsonPath("$.merchantReference",
                        org.hamcrest.Matchers.startsWith("MR-")))
                .andExpect(jsonPath("$.originalReference",
                        org.hamcrest.Matchers.startsWith("PSP-")))   // chain to parent
                .andExpect(jsonPath("$.pspReference").doesNotExist());
    }

    @Test
    void API_012_refundPayment_409_amountOverRemaining_SCH_033() throws Exception {
        Ids ids = newBookingWithRoomLine();
        UUID paymentId = createPayment(ids.bookingId);
        String mref = paymentReference(paymentId);
        driveAuthorisationWebhook(mref, 18000L);
        driveCaptureWebhook(mref, 18000L);

        mvc.perform(post("/payments/" + paymentId + "/refunds")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50000}"))   // > captured
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void API_012_refundPayment_428_withoutHumanAuth() throws Exception {
        Ids ids = newBookingWithRoomLine();
        UUID paymentId = createPayment(ids.bookingId);

        mvc.perform(post("/payments/" + paymentId + "/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isPreconditionRequired());
    }

    // ── RX-003 folio settlement read-model (customerOwes / netRevenue) ────────

    @Test
    void RX_003_paidThenRefunded_customerOwesZero_netRevenueRetained() throws Exception {
        // RX-003 §1 row 3, end-to-end over HTTP: book £600 → pay £600 → refund £100.
        // The refund must NOT reopen a debt: customerOwes stays 0; netRevenue falls 600 → 500.
        UUID customerId = createCustomer();
        UUID productId = productService.createRoom(
                "RX003 Room " + UUID.randomUUID(), 60_000L, "GBP",
                "HIGH", "KING", 2, true, 5).getId();
        UUID bookingId = createBooking(customerId);
        addLine(bookingId, productId);

        UUID paymentId = UUID.fromString(node(mvc.perform(post("/bookings/" + bookingId + "/payments")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":60000}"))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText());
        String mref = paymentReference(paymentId);

        // Pay the full £600: AUTHORISATION then CAPTURE (capture rolls up amountPaid).
        String authPspRef = newPspRef();
        deliverEvent("AUTHORISATION", authPspRef + ":AUTHORISATION:1", mref, authPspRef, 60000L,
                ",\"authExpiresAt\":\"2026-08-04T11:00:00Z\"");
        String capPspRef = newPspRef();
        deliverEvent("CAPTURE", capPspRef + ":CAPTURE:1", mref, capPspRef, 60000L, "");

        mvc.perform(get("/bookings/" + bookingId))
                .andExpect(jsonPath("$.amountPaid").value(60000))
                .andExpect(jsonPath("$.customerOwes").value(0))
                .andExpect(jsonPath("$.netRevenue").value(60000));

        // Refund £100: operator request → REFUND webhook settles + rolls up amountRefunded.
        String refundMref = node(mvc.perform(post("/payments/" + paymentId + "/refunds")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10000,\"reason\":\"Goodwill\"}"))
                .andExpect(status().isAccepted())
                .andReturn()).get("merchantReference").asText();
        String refundPspRef = newPspRef();
        deliverEvent("REFUND", refundPspRef + ":REFUND:1", mref, refundPspRef, 10000L,
                ",\"refundMerchantReference\":\"" + refundMref
                        + "\",\"originalReference\":\"" + authPspRef + "\"");

        // RX-003: customerOwes == 0 (refund cannot make a customer owe more);
        // netRevenue == amountPaid - amountRefunded == 600 - 100 == 500.
        mvc.perform(get("/bookings/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountPaid").value(60000))
                .andExpect(jsonPath("$.amountRefunded").value(10000))
                .andExpect(jsonPath("$.customerOwes").value(0))
                .andExpect(jsonPath("$.netRevenue").value(50000));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private record Ids(UUID customerId, UUID productId, UUID bookingId) {}

    private Ids newBookingWithRoomLine() throws Exception {
        UUID customerId = createCustomer();
        UUID productId = productService.createRoom(
                "Test Room " + UUID.randomUUID(), UNIT_PRICE, "GBP",
                "HIGH", "KING", 2, true, 5).getId();
        UUID bookingId = createBooking(customerId);
        addLine(bookingId, productId);
        return new Ids(customerId, productId, bookingId);
    }

    private UUID createCustomer() throws Exception {
        return UUID.fromString(node(mvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Payment Test\"}"))
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

    private UUID createPayment(UUID bookingId) throws Exception {
        return UUID.fromString(node(mvc.perform(post("/bookings/" + bookingId + "/payments")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":18000}"))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText());
    }

    /** Read the payment's server-minted merchantReference back over the API. */
    private String paymentReference(UUID paymentId) throws Exception {
        return node(mvc.perform(get("/payments/" + paymentId))
                .andExpect(status().isOk())
                .andReturn()).get("merchantReference").asText();
    }

    private static String newPspRef() {
        return "PSP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** Deliver an arbitrary PSP webhook event (caller controls pspReference for chaining). */
    private void deliverEvent(String code, String idKey, String mref, String pspRef,
                              long amount, String extra) throws Exception {
        mvc.perform(post("/webhooks/psp")
                        .header("X-PSP-Signature", "test-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody(code, idKey, mref, pspRef, amount, extra)))
                .andExpect(status().isOk());
    }

    private void driveAuthorisationWebhook(String merchantReference, long amount) throws Exception {
        String pspRef = "PSP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String idKey = pspRef + ":AUTHORISATION:1";
        mvc.perform(post("/webhooks/psp")
                        .header("X-PSP-Signature", "test-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("AUTHORISATION", idKey, merchantReference, pspRef, amount,
                                ",\"authExpiresAt\":\"2026-08-04T11:00:00Z\"")))
                .andExpect(status().isOk());
    }

    private void driveCaptureWebhook(String merchantReference, long amount) throws Exception {
        String pspRef = "PSP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String idKey = pspRef + ":CAPTURE:1";
        mvc.perform(post("/webhooks/psp")
                        .header("X-PSP-Signature", "test-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("CAPTURE", idKey, merchantReference, pspRef, amount, "")))
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
