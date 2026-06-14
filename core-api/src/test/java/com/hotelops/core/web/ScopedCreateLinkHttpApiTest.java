package com.hotelops.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.core.TestcontainersConfiguration;
import com.hotelops.core.common.auth.HumanAuthorizationGate;
import com.hotelops.core.payment.PaymentLine;
import com.hotelops.core.payment.PaymentLineRepository;
import com.hotelops.core.payment.psp.PspGateway;
import com.hotelops.core.payment.psp.dto.PspCreateLinkRequest;
import com.hotelops.core.payment.psp.dto.PspPaymentLinkResponse;
import com.hotelops.core.product.ProductService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 4 Slice 2, Part 1 — proves the optional WHK-016 {@code lineCoverage} field is
 * reachable over HTTP (API-008) and is threaded into the EXISTING
 * {@code PaymentService.createPaymentLink(..., coverage)} overload that persists and
 * sum-validates {@link PaymentLine} rows. The service-layer proof lives in
 * {@link ScopedAllocationApiTest} (untouched); this asserts the HTTP wiring only.
 *
 * Traps guarded here: E (omitted coverage = byte-identical folio-wide behaviour, no
 * payment_line rows), F (lineCoverage is optional — a {@code {amount}}-only body still 201s),
 * C/G (coverage never crosses to payments-sim — the captured {@link PspCreateLinkRequest}
 * has no coverage surface).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ScopedCreateLinkHttpApiTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping scoped create-link HTTP test: no container runtime available.");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService productService;
    @Autowired PaymentLineRepository paymentLineRepository;

    @MockitoBean PspGateway pspGateway;

    @BeforeEach
    void stubPsp() {
        when(pspGateway.createLink(any())).thenAnswer(inv -> new PspPaymentLinkResponse(
                "PL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "PENDING"));
    }

    private static final String HA = HumanAuthorizationGate.HEADER_NAME;
    private static final String HA_OK = "human-confirmed-yes";
    private static final String STARTS_AT = "2027-01-01T15:00:00Z";
    private static final String ENDS_AT   = "2027-01-03T11:00:00Z";
    private static final long ROOM_PRICE  = 180_000L;
    private static final long SPA_PRICE   =  20_000L;

    // ── Scoped create-link over HTTP persists payment_line rows ──────────────

    @Test
    void scopedCreateLink_overHttp_persistsPaymentLineRows() throws Exception {
        UUID roomId = productService.createRoom(
                "HttpScope Room " + UUID.randomUUID(), ROOM_PRICE, "GBP",
                "HIGH", "KING", 2, true, 5).getId();
        UUID spaId = productService.createSpa(
                "HttpScope Massage " + UUID.randomUUID(), SPA_PRICE, "GBP",
                "MASSAGE_60", 60, null, 3).getId();

        UUID bookingId = createBooking(createCustomer());
        addLine(bookingId, roomId);
        addLine(bookingId, spaId);
        UUID roomLineId = lineIdByVertical(bookingId, "ROOM");
        UUID spaLineId  = lineIdByVertical(bookingId, "SPA");

        // Scoped link covering BOTH lines (sum 200000 == amount) supplied over HTTP.
        String body = "{\"amount\":200000,\"captureMode\":\"MANUAL\",\"lineCoverage\":["
                + "{\"bookingLineId\":\"" + roomLineId + "\",\"amount\":180000},"
                + "{\"bookingLineId\":\"" + spaLineId + "\",\"amount\":20000}]}";

        UUID paymentId = UUID.fromString(node(mvc.perform(
                        post("/bookings/" + bookingId + "/payments")
                                .header(HA, HA_OK)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amountRequested").value(200000))
                .andReturn()).get("id").asText());

        List<PaymentLine> lines = paymentLineRepository.findByPaymentId(paymentId);
        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(pl -> pl.getBookingLine().getId(), PaymentLine::getAmount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(roomLineId, 180_000L),
                        org.assertj.core.groups.Tuple.tuple(spaLineId, 20_000L));

        // Trap C/G: coverage is core-side only — it never reaches payments-sim.
        ArgumentCaptor<PspCreateLinkRequest> psp = ArgumentCaptor.forClass(PspCreateLinkRequest.class);
        verify(pspGateway, atLeastOnce()).createLink(psp.capture());
        assertThat(psp.getValue().amount()).isEqualTo(200_000L);  // amount only; no coverage field exists
    }

    // ── Folio-wide create-link (no coverage) — byte-identical, no payment_line ──

    @Test
    void folioWideCreateLink_noCoverage_persistsNoPaymentLineRows() throws Exception {
        UUID roomId = productService.createRoom(
                "HttpNoScope Room " + UUID.randomUUID(), ROOM_PRICE, "GBP",
                "HIGH", "KING", 2, true, 5).getId();
        UUID bookingId = createBooking(createCustomer());
        addLine(bookingId, roomId);

        // Existing-client shape: {amount} only, no lineCoverage (Trap F: optional field).
        UUID paymentId = UUID.fromString(node(mvc.perform(
                        post("/bookings/" + bookingId + "/payments")
                                .header(HA, HA_OK)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":180000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.captureMode").value("MANUAL"))  // RoomStrategy default, unchanged
                .andExpect(jsonPath("$.amountRequested").value(180000))
                .andReturn()).get("id").asText());

        // Trap E: omitted coverage → folio-wide, zero scoped rows.
        assertThat(paymentLineRepository.findByPaymentId(paymentId)).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UUID createCustomer() throws Exception {
        return UUID.fromString(node(mvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Http Scope Test\"}"))
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

    private JsonNode node(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
