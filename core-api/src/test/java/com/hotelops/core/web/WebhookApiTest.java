package com.hotelops.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.core.TestcontainersConfiguration;
import com.hotelops.core.common.auth.HumanAuthorizationGate;
import com.hotelops.core.ledger.LedgerPosting;
import com.hotelops.core.ledger.LedgerPostingRepository;
import com.hotelops.core.ledger.OutboxProcessor;
import com.hotelops.core.payment.webhook.WebhookInbox;
import com.hotelops.core.payment.webhook.WebhookInboxRepository;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Inbound PSP webhook over real HTTP — API-013 + WHK-001..014 (WAVE0_03 §4).
 *
 * Asserts: signature presence-check (401, WHK-014); inbox dedupe (WHK-005); unknown-ref
 * ack with no mutation (WHK-004); WHK-006..011 transitions; per-line REVENUE postings
 * after CAPTURE (WHK-007 / WHK-012); per-line REFUND_REVERSAL after REFUND (WHK-009);
 * AUTH_EXPIRY ignored on a captured payment (WHK-011).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WebhookApiTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping HTTP webhook test: no container runtime available.");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService productService;
    @Autowired WebhookInboxRepository inboxRepository;
    @Autowired LedgerPostingRepository ledgerRepository;
    @Autowired OutboxProcessor outboxProcessor;

    private static final String HA = HumanAuthorizationGate.HEADER_NAME;
    private static final String HA_OK = "human-confirmed-yes";
    private static final String STARTS_AT = "2026-09-01T15:00:00Z";
    private static final String ENDS_AT   = "2026-09-03T11:00:00Z";

    // ── WHK-014 signature ────────────────────────────────────────────────────

    @Test
    void WHK_014_signature_missing_returns_401_andDoesNotPersistInboxRow() throws Exception {
        long before = inboxRepository.count();

        mvc.perform(post("/webhooks/psp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("AUTHORISATION", "PSP-NOSIG-0000000:AUTHORISATION:1",
                                "MR-NOSIG-1", "PSP-NOSIG-0000000", 1000L, "")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));

        assertThat(inboxRepository.count())
                .as("401 must short-circuit before persisting an inbox row")
                .isEqualTo(before);
    }

    // ── WHK-006 AUTHORISATION ────────────────────────────────────────────────

    @Test
    void WHK_006_authorisation_transitionsPayment_andStampsRefs_noOutboxNoPostings() throws Exception {
        Setup setup = newPaymentReadyForAuth(18000L);
        long postingsBefore = ledgerRepository.count();

        String pspRef = newPspRef();
        deliver("AUTHORISATION", pspRef + ":AUTHORISATION:1", setup.mref, pspRef, 18000L,
                ",\"authExpiresAt\":\"2026-09-04T11:00:00Z\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        mvc.perform(get("/payments/" + setup.paymentId))
                .andExpect(jsonPath("$.status").value("AUTHORISED"))
                .andExpect(jsonPath("$.pspReference").value(pspRef))
                .andExpect(jsonPath("$.amountAuthorised").value(18000));

        // INV-006: no posting on AUTHORISATION.
        outboxProcessor.processPending();
        assertThat(ledgerRepository.count()).isEqualTo(postingsBefore);
    }

    // ── WHK-005 dedupe ───────────────────────────────────────────────────────

    @Test
    void WHK_005_replay_is_idempotent_oneInboxRow_noSecondEffect() throws Exception {
        Setup setup = newPaymentReadyForAuth(18000L);
        String pspRef = newPspRef();
        String idKey = pspRef + ":AUTHORISATION:1";

        deliver("AUTHORISATION", idKey, setup.mref, pspRef, 18000L,
                ",\"authExpiresAt\":\"2026-09-04T11:00:00Z\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        // Same idempotencyKey, same effect → 200 duplicate=true, no second mutation.
        deliver("AUTHORISATION", idKey, setup.mref, pspRef, 18000L,
                ",\"authExpiresAt\":\"2026-09-04T11:00:00Z\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        assertThat(inboxRepository.findByIdempotencyKey(idKey)).isPresent();
        long matching = inboxRepository.findAll().stream()
                .filter(w -> idKey.equals(w.getIdempotencyKey()))
                .count();
        assertThat(matching).isEqualTo(1L);
    }

    // ── WHK-004 unknown reference ────────────────────────────────────────────

    @Test
    void WHK_004_unknownMerchantReference_ackedWithNoMutation_inboxRowRemains() throws Exception {
        long postingsBefore = ledgerRepository.count();
        String pspRef = newPspRef();
        String idKey = pspRef + ":AUTHORISATION:1";

        deliver("AUTHORISATION", idKey, "MR-UNKNOWN-NEVER-CREATED", pspRef, 9999L,
                ",\"authExpiresAt\":\"2026-09-04T11:00:00Z\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        // Inbox row was persisted for audit.
        assertThat(inboxRepository.findByIdempotencyKey(idKey)).isPresent();
        // No ledger effect.
        outboxProcessor.processPending();
        assertThat(ledgerRepository.count()).isEqualTo(postingsBefore);
    }

    // ── WHK-007 / WHK-012 per-line REVENUE postings ──────────────────────────

    @Test
    void WHK_007_capture_drivesOnePostingPerCoveredLine_perLineVerticalAttribution() throws Exception {
        // Two ROOM lines (Stage 1 has only the room strategy registered). Line A created
        // first → filled first per WHK-012. Lines have different lineAmounts so we can
        // assert allocation.
        UUID customerId = createCustomer();
        UUID productA = productService.createRoom(
                "Room A " + UUID.randomUUID(), 50000L, "GBP",
                "HIGH", "KING", 2, true, 3).getId();
        UUID productB = productService.createRoom(
                "Room B " + UUID.randomUUID(), 20000L, "GBP",
                "LOW", "QUEEN", 2, false, 3).getId();
        UUID bookingId = createBooking(customerId);
        addLine(bookingId, productA, 1);
        Thread.sleep(10);   // ensure createdAt ordering is deterministic
        addLine(bookingId, productB, 1);

        UUID paymentId = createPayment(bookingId, 70000L);
        String mref = paymentReference(paymentId);

        // Drive AUTHORISATION + CAPTURE for the full 70000.
        deliverAuth(mref, 70000L);
        deliverCapture(mref, 70000L);

        // Outbox tick → LedgerService.postCapture → per-line REVENUE postings.
        outboxProcessor.processPending();

        List<LedgerPosting> postings = ledgerRepository.findAll().stream()
                .filter(p -> mref.equals(p.getMerchantReference()))
                .toList();

        // Fill-by-line-order: A (50000) filled first, then B (20000). Sum = 70000.
        assertThat(postings).hasSize(2);
        assertThat(postings.stream().mapToLong(LedgerPosting::getAmount).sum())
                .isEqualTo(70000L);
        assertThat(postings).allMatch(p -> p.getBookingLine() != null);
        assertThat(postings).allMatch(p -> "REVENUE".equals(p.getPostingType().name()));
    }

    // ── WHK-008 CANCELLATION ─────────────────────────────────────────────────

    @Test
    void WHK_008_cancellation_setsCANCELLED_noPosting() throws Exception {
        Setup setup = newPaymentReadyForAuth(18000L);
        deliverAuth(setup.mref, 18000L);

        long postingsBefore = ledgerRepository.count();

        String pspRef = newPspRef();
        deliver("CANCELLATION", pspRef + ":CANCELLATION:1", setup.mref, pspRef, 0L, "")
                .andExpect(status().isOk());

        mvc.perform(get("/payments/" + setup.paymentId))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        outboxProcessor.processPending();
        assertThat(ledgerRepository.count()).isEqualTo(postingsBefore);
    }

    // ── WHK-009 REFUND ───────────────────────────────────────────────────────

    @Test
    void WHK_009_refund_drivesPerLineRefundReversalPostings() throws Exception {
        Setup setup = newPaymentReadyForAuth(18000L);
        String authPspRef = deliverAuth(setup.mref, 18000L);
        deliverCapture(setup.mref, 18000L);
        outboxProcessor.processPending();   // drain REVENUE postings

        // Request a refund over the operator endpoint → PENDING refund row.
        MvcResult res = mvc.perform(post("/payments/" + setup.paymentId + "/refunds")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":5000}"))
                .andExpect(status().isAccepted())
                .andReturn();
        String refundMref = node(res).get("merchantReference").asText();

        // REFUND webhook settles the refund and drives REFUND_REVERSAL postings.
        String refundPspRef = newPspRef();
        deliver("REFUND", refundPspRef + ":REFUND:1", setup.mref, refundPspRef, 5000L,
                ",\"refundMerchantReference\":\"" + refundMref
                + "\",\"originalReference\":\"" + authPspRef + "\"")
                .andExpect(status().isOk());

        outboxProcessor.processPending();

        List<LedgerPosting> reversals = ledgerRepository.findAll().stream()
                .filter(p -> "REFUND_REVERSAL".equals(p.getPostingType().name()))
                .filter(p -> refundMref.equals(p.getMerchantReference()))
                .toList();
        assertThat(reversals).isNotEmpty();
        assertThat(reversals.stream().mapToLong(LedgerPosting::getAmount).sum())
                .isEqualTo(-5000L);

        mvc.perform(get("/payments/" + setup.paymentId))
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.amountRefunded").value(5000));
    }

    // ── WHK-010 *_FAILED ─────────────────────────────────────────────────────

    @Test
    void WHK_010_capture_failed_marksPayment_noPostings() throws Exception {
        Setup setup = newPaymentReadyForAuth(18000L);
        deliverAuth(setup.mref, 18000L);
        long before = ledgerRepository.count();

        String pspRef = newPspRef();
        deliver("CAPTURE_FAILED", pspRef + ":CAPTURE_FAILED:1", setup.mref, pspRef, 18000L,
                ",\"reason\":\"insufficient_funds\"")
                .andExpect(status().isOk());

        mvc.perform(get("/payments/" + setup.paymentId))
                .andExpect(jsonPath("$.status").value("CAPTURE_FAILED"));

        outboxProcessor.processPending();
        assertThat(ledgerRepository.count()).isEqualTo(before);
    }

    @Test
    void WHK_010_refund_failed_marksRefund_paymentStatusUnchanged() throws Exception {
        Setup setup = newPaymentReadyForAuth(18000L);
        String authPspRef = deliverAuth(setup.mref, 18000L);
        deliverCapture(setup.mref, 18000L);
        outboxProcessor.processPending();

        MvcResult res = mvc.perform(post("/payments/" + setup.paymentId + "/refunds")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":4000}"))
                .andExpect(status().isAccepted())
                .andReturn();
        String refundMref = node(res).get("merchantReference").asText();

        String pspRef = newPspRef();
        deliver("REFUND_FAILED", pspRef + ":REFUND_FAILED:1", setup.mref, pspRef, 4000L,
                ",\"refundMerchantReference\":\"" + refundMref
                + "\",\"originalReference\":\"" + authPspRef + "\""
                + ",\"reason\":\"network_timeout\"")
                .andExpect(status().isOk());

        mvc.perform(get("/payments/" + setup.paymentId))
                .andExpect(jsonPath("$.status").value("CAPTURED"));   // payment unchanged
    }

    // ── WHK-011 AUTH_EXPIRY ──────────────────────────────────────────────────

    @Test
    void WHK_011_authExpiry_flipsAuthorisedToAuthExpired() throws Exception {
        Setup setup = newPaymentReadyForAuth(18000L);
        deliverAuth(setup.mref, 18000L);

        String pspRef = newPspRef();
        deliver("AUTH_EXPIRY", pspRef + ":AUTH_EXPIRY:1", setup.mref, pspRef, 0L, "")
                .andExpect(status().isOk());

        mvc.perform(get("/payments/" + setup.paymentId))
                .andExpect(jsonPath("$.status").value("AUTH_EXPIRED"));
    }

    @Test
    void WHK_011_authExpiry_onCapturedPayment_isIgnored() throws Exception {
        Setup setup = newPaymentReadyForAuth(18000L);
        deliverAuth(setup.mref, 18000L);
        deliverCapture(setup.mref, 18000L);

        String pspRef = newPspRef();
        deliver("AUTH_EXPIRY", pspRef + ":AUTH_EXPIRY:1", setup.mref, pspRef, 0L, "")
                .andExpect(status().isOk());

        mvc.perform(get("/payments/" + setup.paymentId))
                .andExpect(jsonPath("$.status").value("CAPTURED"));   // unchanged
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private record Setup(UUID paymentId, String mref) {}

    private Setup newPaymentReadyForAuth(long amount) throws Exception {
        UUID customerId = createCustomer();
        UUID productId = productService.createRoom(
                "Webhook Room " + UUID.randomUUID(), amount, "GBP",
                "HIGH", "KING", 2, true, 5).getId();
        UUID bookingId = createBooking(customerId);
        addLine(bookingId, productId, 1);
        UUID paymentId = createPayment(bookingId, amount);
        String mref = paymentReference(paymentId);
        return new Setup(paymentId, mref);
    }

    /** Delivers an AUTHORISATION webhook and returns the pspReference it stamped. */
    private String deliverAuth(String mref, long amount) throws Exception {
        String pspRef = newPspRef();
        deliver("AUTHORISATION", pspRef + ":AUTHORISATION:1", mref, pspRef, amount,
                ",\"authExpiresAt\":\"2026-09-04T11:00:00Z\"")
                .andExpect(status().isOk());
        return pspRef;
    }

    private void deliverCapture(String mref, long amount) throws Exception {
        String pspRef = newPspRef();
        deliver("CAPTURE", pspRef + ":CAPTURE:1", mref, pspRef, amount, "")
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions deliver(
            String code, String idKey, String mref, String pspRef, long amount, String extra)
            throws Exception {
        return mvc.perform(post("/webhooks/psp")
                .header("X-PSP-Signature", "test-signature")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventBody(code, idKey, mref, pspRef, amount, extra)));
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
                + "\"occurredAt\":\"2026-09-01T15:00:00Z\","
                + "\"success\":" + (code.endsWith("_FAILED") ? "false" : "true")
                + extra
                + "}";
    }

    private static String newPspRef() {
        return "PSP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private UUID createCustomer() throws Exception {
        return UUID.fromString(node(mvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Webhook Test\"}"))
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

    private void addLine(UUID bookingId, UUID productId, int quantity) throws Exception {
        mvc.perform(post("/bookings/" + bookingId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"startsAt\":\"" + STARTS_AT
                                + "\",\"endsAt\":\"" + ENDS_AT + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isCreated());
    }

    private UUID createPayment(UUID bookingId, long amount) throws Exception {
        return UUID.fromString(node(mvc.perform(post("/bookings/" + bookingId + "/payments")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText());
    }

    private String paymentReference(UUID paymentId) throws Exception {
        return node(mvc.perform(get("/payments/" + paymentId))
                .andExpect(status().isOk())
                .andReturn()).get("merchantReference").asText();
    }

    private JsonNode node(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
