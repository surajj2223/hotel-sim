package com.hotelops.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.core.TestcontainersConfiguration;
import com.hotelops.core.common.auth.HumanAuthorizationGate;
import com.hotelops.core.common.enums.PostingType;
import com.hotelops.core.common.enums.Vertical;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice B1 — proves the IMMEDIATE capture two-event path end-to-end inside core-api.
 *
 * Under the two-event model, IMMEDIATE flows through the identical machinery as MANUAL:
 *   AUTHORISATION webhook (WHK-006) → stamps amountAuthorised, PENDING→AUTHORISED, no posting.
 *   CAPTURE webhook       (WHK-007) → stamps amountCaptured, enqueues PAYMENT_CAPTURED outbox.
 *   Outbox tick           (WHK-013) → LedgerService.postCapture → one REVENUE posting per
 *                                      active booking line (WHK-012, GAP-1 fix).
 *
 * The SPA vertical's defaultCaptureMode() == IMMEDIATE (ENM-004, SpaStrategy).
 * The final ledger shape (one REVENUE posting, correct vertical, correct line attribution)
 * is identical to the MANUAL path proven by WebhookApiTest#WHK_007_*.
 *
 * No production code is changed. The PENDING special-case in assertCapturable
 * (out-of-order webhook guard) is left intact per the named-trap note.
 *
 * Contract references: ENM-004, WHK-006, WHK-007, WHK-012, WHK-013, INV-005, INV-006, SCH-032.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ImmediateCaptureApiTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping IMMEDIATE capture test: no container runtime available.");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService productService;
    @Autowired LedgerPostingRepository ledgerRepository;
    @Autowired OutboxProcessor outboxProcessor;

    // Stub the outbound PSP call — this suite proves core-api's inbound webhook path.
    // The real outbound seam is covered by PspOutboundIntegrationTest.
    @MockitoBean PspGateway pspGateway;

    @BeforeEach
    void stubPsp() {
        when(pspGateway.createLink(any())).thenAnswer(inv -> new PspPaymentLinkResponse(
                "PL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "PENDING"));
    }

    private static final String HA      = HumanAuthorizationGate.HEADER_NAME;
    private static final String HA_OK   = "human-confirmed-yes";
    // October window — distinct from PaymentApiTest (August) and WebhookApiTest (September)
    // so per-mref ledger filtering is unambiguous on the shared Testcontainers DB.
    private static final String STARTS_AT = "2026-10-01T10:00:00Z";
    private static final String ENDS_AT   = "2026-10-01T11:00:00Z";
    private static final long   SPA_PRICE = 9_500L;

    /**
     * ENM-004 / WHK-006 / WHK-007 / INV-006:
     * IMMEDIATE two-event sequence → exactly one per-line REVENUE posting, vertical=SPA,
     * amount=captured, bookingLine set. Ledger shape is identical to the MANUAL path.
     */
    @Test
    void ENM004_immediate_twoEvent_authThenCapture_oneRevenuePosting_identicalInShapeToManual()
            throws Exception {

        // 1. Seed SPA product (concurrentSlots=3; price snapshot = SPA_PRICE).
        UUID spaId = productService.createSpa(
                "Swedish Massage 60min " + UUID.randomUUID(),
                SPA_PRICE, "GBP", "MASSAGE_60", 60, null, 3).getId();

        // 2. Build folio: customer → booking → one SPA line.
        UUID customerId = createCustomer();
        UUID bookingId  = createBooking(customerId);
        addLine(bookingId, spaId, 1);

        // 3. Create payment link — no captureMode override; strategy must default to IMMEDIATE.
        MvcResult paymentResult = mvc.perform(post("/bookings/" + bookingId + "/payments")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + SPA_PRICE + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.captureMode").value("IMMEDIATE"))  // ENM-004 defaulting
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amountAuthorised").value(0))
                .andExpect(jsonPath("$.amountCaptured").value(0))
                .andReturn();

        UUID   paymentId = UUID.fromString(node(paymentResult).get("id").asText());
        String mref      = node(paymentResult).get("merchantReference").asText();

        // 4. AUTHORISATION webhook (WHK-006) — stamps refs, PENDING→AUTHORISED, no posting.
        String authPspRef = newPspRef();
        mvc.perform(post("/webhooks/psp")
                        .header("X-PSP-Signature", "test-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("AUTHORISATION", authPspRef + ":AUTHORISATION:1",
                                mref, authPspRef, SPA_PRICE,
                                ",\"authExpiresAt\":\"2026-10-01T12:00:00Z\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        mvc.perform(get("/payments/" + paymentId))
                .andExpect(jsonPath("$.status").value("AUTHORISED"))
                .andExpect(jsonPath("$.amountAuthorised").value(SPA_PRICE))
                .andExpect(jsonPath("$.pspReference").value(authPspRef));

        // INV-006: AUTHORISATION produces no ledger posting.
        outboxProcessor.processPending();
        assertThat(postingsFor(mref))
                .as("AUTHORISATION must not produce a ledger posting (INV-006)")
                .isEmpty();

        // 5. CAPTURE webhook (WHK-007) — stamps amountCaptured, AUTHORISED→CAPTURED, enqueues outbox.
        String capturePspRef = newPspRef();
        mvc.perform(post("/webhooks/psp")
                        .header("X-PSP-Signature", "test-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("CAPTURE", capturePspRef + ":CAPTURE:1",
                                mref, capturePspRef, SPA_PRICE, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        mvc.perform(get("/payments/" + paymentId))
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.amountCaptured").value(SPA_PRICE));

        // 6. Drain outbox → LedgerService.postCapture.
        outboxProcessor.processPending();

        // 7. Ledger: exactly one REVENUE posting, vertical=SPA, full amount, line-attributed.
        List<LedgerPosting> postings = postingsFor(mref);

        assertThat(postings)
                .as("IMMEDIATE capture must produce exactly one REVENUE posting (no double-post; INV-005)")
                .hasSize(1);

        LedgerPosting posting = postings.get(0);
        assertThat(posting.getPostingType())
                .as("posting type must be REVENUE (INV-006, WHK-007)")
                .isEqualTo(PostingType.REVENUE);
        assertThat(posting.getVertical())
                .as("vertical must be SPA — per-line attribution carries the line's vertical (WHK-012, GAP-1 fix)")
                .isEqualTo(Vertical.SPA);
        assertThat(posting.getAmount())
                .as("posted amount must equal captured amount (BIGINT minor units)")
                .isEqualTo(SPA_PRICE);
        assertThat(posting.getBookingLine())
                .as("posting must carry bookingLine (per-line, not folio-level; WHK-012)")
                .isNotNull();

        // 8. Booking balance — amountPaid rolls up to SPA_PRICE, balance == 0.
        mvc.perform(get("/bookings/" + bookingId))
                .andExpect(jsonPath("$.amountPaid").value(SPA_PRICE))
                .andExpect(jsonPath("$.balance").value(0));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<LedgerPosting> postingsFor(String mref) {
        return ledgerRepository.findAll().stream()
                .filter(p -> mref.equals(p.getMerchantReference()))
                .toList();
    }

    private UUID createCustomer() throws Exception {
        return UUID.fromString(node(mvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Immediate Capture Test\"}"))
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
                        .content("{\"productId\":\"" + productId + "\",\"startsAt\":\""
                                + STARTS_AT + "\",\"endsAt\":\"" + ENDS_AT
                                + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isCreated());
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
                + "\"occurredAt\":\"2026-10-01T10:30:00Z\","
                + "\"success\":true"
                + extra
                + "}";
    }

    private JsonNode node(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
