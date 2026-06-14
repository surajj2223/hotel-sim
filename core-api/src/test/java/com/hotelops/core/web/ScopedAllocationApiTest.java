package com.hotelops.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.core.TestcontainersConfiguration;
import com.hotelops.core.common.enums.CaptureMode;
import com.hotelops.core.common.enums.PostingType;
import com.hotelops.core.common.enums.Vertical;
import com.hotelops.core.ledger.LedgerPosting;
import com.hotelops.core.ledger.LedgerPostingRepository;
import com.hotelops.core.ledger.OutboxProcessor;
import com.hotelops.core.payment.LineCoverage;
import com.hotelops.core.payment.Payment;
import com.hotelops.core.payment.PaymentService;
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
 * Stage 4 Slice 1 — proves WHK-016 scoped payment→line allocation and the live folio
 * {@code amountAuthorised} roll-up (D3) end-to-end inside core-api.
 *
 * The webhook/outbox machinery is identical to {@link ImmediateCaptureApiTest}; the only new
 * ingredient is scoped coverage, supplied at the service layer ({@link PaymentService}
 * accepts an optional {@link LineCoverage} list — API-008's frozen HTTP DTO is unchanged, so
 * the operator-facing surface stays folio-wide pending a separate API-contract change). The
 * settlement (auth/capture webhooks) and ledger drain still run over the real HTTP seam.
 *
 * Contract references: WHK-016 (§5.1), WHK-012 (fallback), WHK-007, INV-004, INV-006, D3.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ScopedAllocationApiTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping scoped-allocation test: no container runtime available.");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService productService;
    @Autowired PaymentService paymentService;
    @Autowired LedgerPostingRepository ledgerRepository;
    @Autowired OutboxProcessor outboxProcessor;

    @MockitoBean PspGateway pspGateway;

    @BeforeEach
    void stubPsp() {
        when(pspGateway.createLink(any())).thenAnswer(inv -> new PspPaymentLinkResponse(
                "PL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "PENDING"));
    }

    // December window — distinct from PaymentApiTest (Aug), WebhookApiTest (Sep),
    // ImmediateCaptureApiTest (Oct) so per-booking ledger filtering is unambiguous.
    private static final String STARTS_AT = "2026-12-01T15:00:00Z";
    private static final String ENDS_AT   = "2026-12-03T11:00:00Z";
    private static final long ROOM_PRICE  = 180_000L;  // £1,800
    private static final long SPA_PRICE   =  20_000L;  // £200

    // ── Proof 1: sequential cross-vertical ───────────────────────────────────

    @Test
    void WHK016_sequentialCrossVertical_spaPaymentCreditsSpaNotRoom_andAuthorisedGapIsVisible()
            throws Exception {
        UUID roomId = productService.createRoom(
                "Scoped Room " + UUID.randomUUID(), ROOM_PRICE, "GBP",
                "HIGH", "KING", 2, true, 5).getId();
        UUID spaId = productService.createSpa(
                "Scoped Massage " + UUID.randomUUID(), SPA_PRICE, "GBP",
                "MASSAGE_60", 60, null, 3).getId();

        UUID customerId = createCustomer();
        UUID bookingId  = createBooking(customerId);

        // 1. Room line £1,800 → MANUAL auth £1,800 scoped to the room line.
        addLine(bookingId, roomId, 1);
        UUID roomLineId = lineIdByVertical(bookingId, "ROOM");
        Payment roomPayment = paymentService.createPaymentLink(bookingId, ROOM_PRICE, "GBP",
                CaptureMode.MANUAL, List.of(new LineCoverage(roomLineId, ROOM_PRICE)));
        driveAuthorisationWebhook(roomPayment.getMerchantReference(), ROOM_PRICE);

        assertFolio(bookingId, ROOM_PRICE, ROOM_PRICE, 0L);  // total/authorised 180000, paid 0

        // 2. Add spa line £200 → total rises to £2,000 but authorised stays £1,800 (the gap).
        addLine(bookingId, spaId, 1);
        assertFolio(bookingId, ROOM_PRICE + SPA_PRICE, ROOM_PRICE, 0L);  // total 200000, authorised 180000

        // 3. IMMEDIATE £200 payment scoped to spa. Per the two-event model, IMMEDIATE flows
        //    AUTHORISATION → CAPTURE (the schema enforces captured <= authorised), so the spa
        //    payment's auth legitimately joins the authorised roll-up once it lands.
        UUID spaLineId = lineIdByVertical(bookingId, "SPA");
        Payment spaPayment = paymentService.createPaymentLink(bookingId, SPA_PRICE, "GBP",
                CaptureMode.IMMEDIATE, List.of(new LineCoverage(spaLineId, SPA_PRICE)));
        driveAuthorisationWebhook(spaPayment.getMerchantReference(), SPA_PRICE);
        driveCaptureWebhook(spaPayment.getMerchantReference(), SPA_PRICE);
        outboxProcessor.processPending();

        // (a) exactly one REVENUE posting, vertical SPA, on the spa line.
        List<LedgerPosting> spaPostings = postingsFor(spaPayment.getMerchantReference());
        assertThat(spaPostings).hasSize(1);
        LedgerPosting posting = spaPostings.get(0);
        assertThat(posting.getPostingType()).isEqualTo(PostingType.REVENUE);
        assertThat(posting.getVertical()).isEqualTo(Vertical.SPA);
        assertThat(posting.getAmount()).isEqualTo(SPA_PRICE);
        assertThat(posting.getBookingLine().getId()).isEqualTo(spaLineId);

        // (b) the room line received NO revenue (its payment was only authorised, never captured).
        assertThat(postingsForBooking(bookingId))
                .as("only the spa line earned revenue; the room line is untouched")
                .allMatch(p -> p.getVertical() == Vertical.SPA);

        // (c) folio: total 200000, paid 20000 (spa captured), balance 180000. Authorised is now
        //     200000 (room 180000 + spa 20000) — the spa auth joined the roll-up. The visible
        //     under-secured GAP was the assertion above (authorised 180000 < total 200000),
        //     before the spa was secured.
        assertFolio(bookingId, ROOM_PRICE + SPA_PRICE, ROOM_PRICE + SPA_PRICE, SPA_PRICE);
    }

    // ── Proof 2: multi-method (room on one payment, spa on another) ───────────

    @Test
    void WHK016_multiMethod_eachLineCreditedToItsOwnVertical_withDistinctPspRefs() throws Exception {
        UUID roomId = productService.createRoom(
                "MM Room " + UUID.randomUUID(), ROOM_PRICE, "GBP",
                "HIGH", "KING", 2, true, 5).getId();
        UUID spaId = productService.createSpa(
                "MM Massage " + UUID.randomUUID(), SPA_PRICE, "GBP",
                "MASSAGE_60", 60, null, 3).getId();

        UUID customerId = createCustomer();
        UUID bookingId  = createBooking(customerId);
        addLine(bookingId, roomId, 1);
        addLine(bookingId, spaId, 1);
        UUID roomLineId = lineIdByVertical(bookingId, "ROOM");
        UUID spaLineId  = lineIdByVertical(bookingId, "SPA");

        // Payment A → room (Visa), MANUAL auth+capture £1,800.
        Payment paymentA = paymentService.createPaymentLink(bookingId, ROOM_PRICE, "GBP",
                CaptureMode.MANUAL, List.of(new LineCoverage(roomLineId, ROOM_PRICE)));
        driveAuthorisationWebhook(paymentA.getMerchantReference(), ROOM_PRICE);
        String pspA = driveCaptureWebhook(paymentA.getMerchantReference(), ROOM_PRICE);

        // Payment B → spa (Amex), IMMEDIATE auth+capture £200 (two-event model).
        Payment paymentB = paymentService.createPaymentLink(bookingId, SPA_PRICE, "GBP",
                CaptureMode.IMMEDIATE, List.of(new LineCoverage(spaLineId, SPA_PRICE)));
        driveAuthorisationWebhook(paymentB.getMerchantReference(), SPA_PRICE);
        String pspB = driveCaptureWebhook(paymentB.getMerchantReference(), SPA_PRICE);

        outboxProcessor.processPending();

        LedgerPosting roomPosting = single(postingsFor(paymentA.getMerchantReference()));
        assertThat(roomPosting.getVertical()).isEqualTo(Vertical.ROOM);
        assertThat(roomPosting.getAmount()).isEqualTo(ROOM_PRICE);
        assertThat(roomPosting.getBookingLine().getId()).isEqualTo(roomLineId);
        assertThat(roomPosting.getPspReference()).isEqualTo(pspA);

        LedgerPosting spaPosting = single(postingsFor(paymentB.getMerchantReference()));
        assertThat(spaPosting.getVertical()).isEqualTo(Vertical.SPA);
        assertThat(spaPosting.getAmount()).isEqualTo(SPA_PRICE);
        assertThat(spaPosting.getBookingLine().getId()).isEqualTo(spaLineId);
        assertThat(spaPosting.getPspReference()).isEqualTo(pspB);

        // Two distinct PSP transactions, each traceable to its own vertical.
        assertThat(pspA).isNotEqualTo(pspB);

        // Folio fully authorised and fully paid (£2,000), balance 0.
        assertFolio(bookingId, ROOM_PRICE + SPA_PRICE, ROOM_PRICE + SPA_PRICE, ROOM_PRICE + SPA_PRICE);
    }

    // ── Proof 3: no-scope regression — WHK-012 fill-by-line-order unchanged ───

    @Test
    void WHK012_noScopePayment_partialCapture54000_fillsRoomFirstExactly() throws Exception {
        final long roomPrice = 50_000L; // £500, created first
        final long spaPrice  = 20_000L; // £200, created second
        UUID roomId = productService.createRoom(
                "Fallback Room " + UUID.randomUUID(), roomPrice, "GBP",
                "HIGH", "KING", 2, true, 5).getId();
        UUID spaId = productService.createSpa(
                "Fallback Massage " + UUID.randomUUID(), spaPrice, "GBP",
                "MASSAGE_60", 60, null, 3).getId();

        UUID customerId = createCustomer();
        UUID bookingId  = createBooking(customerId);
        addLine(bookingId, roomId, 1);
        addLine(bookingId, spaId, 1);
        UUID roomLineId = lineIdByVertical(bookingId, "ROOM");
        UUID spaLineId  = lineIdByVertical(bookingId, "SPA");

        // Folio-wide payment (NO coverage) — auth 70000, partial capture 54000.
        Payment payment = paymentService.createPaymentLink(bookingId, 70_000L, "GBP",
                CaptureMode.MANUAL);
        driveAuthorisationWebhook(payment.getMerchantReference(), 70_000L);
        driveCaptureWebhook(payment.getMerchantReference(), 54_000L);
        outboxProcessor.processPending();

        // WHK-012 §5: R→50000 (REVENUE/ROOM), S→4000 (REVENUE/SPA). Sum 54000.
        List<LedgerPosting> postings = postingsFor(payment.getMerchantReference());
        assertThat(postings).hasSize(2);

        LedgerPosting room = postings.stream()
                .filter(p -> p.getBookingLine().getId().equals(roomLineId)).findFirst().orElseThrow();
        LedgerPosting spa = postings.stream()
                .filter(p -> p.getBookingLine().getId().equals(spaLineId)).findFirst().orElseThrow();
        assertThat(room.getVertical()).isEqualTo(Vertical.ROOM);
        assertThat(room.getAmount()).isEqualTo(50_000L);
        assertThat(spa.getVertical()).isEqualTo(Vertical.SPA);
        assertThat(spa.getAmount()).isEqualTo(4_000L);
        assertThat(postings.stream().mapToLong(LedgerPosting::getAmount).sum()).isEqualTo(54_000L);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static LedgerPosting single(List<LedgerPosting> postings) {
        assertThat(postings).hasSize(1);
        return postings.get(0);
    }

    private List<LedgerPosting> postingsFor(String mref) {
        return ledgerRepository.findAll().stream()
                .filter(p -> mref.equals(p.getMerchantReference()))
                .toList();
    }

    private List<LedgerPosting> postingsForBooking(UUID bookingId) {
        return ledgerRepository.findAll().stream()
                .filter(p -> bookingId.equals(p.getBooking().getId()))
                .toList();
    }

    /** Asserts the folio's server-derived roll-ups (minor units). */
    private void assertFolio(UUID bookingId, long total, long authorised, long paid) throws Exception {
        mvc.perform(get("/bookings/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(total))
                .andExpect(jsonPath("$.amountAuthorised").value(authorised))
                .andExpect(jsonPath("$.amountPaid").value(paid))
                .andExpect(jsonPath("$.balance").value(total - paid));
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
                        .content("{\"fullName\":\"Scoped Allocation Test\"}"))
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

    private void driveAuthorisationWebhook(String mref, long amount) throws Exception {
        String pspRef = newPspRef();
        mvc.perform(post("/webhooks/psp")
                        .header("X-PSP-Signature", "test-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("AUTHORISATION", pspRef + ":AUTHORISATION:1", mref, pspRef,
                                amount, ",\"authExpiresAt\":\"2026-12-04T11:00:00Z\"")))
                .andExpect(status().isOk());
    }

    /** Drives a CAPTURE webhook and returns the capture's pspReference (stamped on the payment). */
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
                + "\"occurredAt\":\"2026-12-01T15:30:00Z\","
                + "\"success\":true"
                + extra
                + "}";
    }

    private JsonNode node(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
