package com.hotelops.core.payment.psp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.core.TestcontainersConfiguration;
import com.hotelops.core.common.auth.HumanAuthorizationGate;
import com.hotelops.core.common.enums.PaymentStatus;
import com.hotelops.core.payment.Payment;
import com.hotelops.core.payment.PaymentRepository;
import com.hotelops.core.product.ProductService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PSP-001 happy path + PSP-007 fail-loud over the real {@link PspGateway}, against an
 * in-process JDK {@link HttpServer} standing in for {@code payments-sim}. Proves the
 * outbound client speaks PSP-001 correctly (stamps {@code paymentLinkId}) and that a PSP
 * rejection surfaces as an operator {@code 502} with the payment row left {@code PENDING}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PspOutboundIntegrationTest {

    /** Programmable PSP stand-in; response status/body set per test. */
    private static HttpServer stub;
    private static volatile int stubStatus = 201;
    private static volatile String stubBody = "{}";

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping: no container runtime available.");
        }
    }

    @DynamicPropertySource
    static void pspBaseUrl(DynamicPropertyRegistry registry) throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            byte[] body = stubBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(stubStatus, body.length == 0 ? -1 : body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stub.start();
        registry.add("payments-sim.base-url",
                () -> "http://127.0.0.1:" + stub.getAddress().getPort());
        registry.add("payments-sim.api-key", () -> "test-psp-key");
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) stub.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService productService;
    @Autowired PaymentRepository paymentRepository;

    private static final String HA = HumanAuthorizationGate.HEADER_NAME;
    private static final String HA_OK = "human-confirmed-yes";

    @Test
    void PSP_001_createPaymentLink_stampsPspMintedPaymentLinkId() throws Exception {
        UUID bookingId = newBookingWithRoomLine();
        String mintedLink = "PL-abc123def456ghij";
        stubStatus = 201;
        stubBody = "{\"paymentLinkId\":\"" + mintedLink + "\",\"merchantReference\":\"MR-x\","
                + "\"status\":\"PENDING\",\"hostedUrl\":\"http://stub/checkout/" + mintedLink + "\"}";

        mvc.perform(post("/bookings/" + bookingId + "/payments")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":18000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                // The paymentLinkId came from the PSP-001 response (tx2 stamp), not invented.
                .andExpect(jsonPath("$.paymentLinkId").value(mintedLink));

        Payment p = paymentRepository.findByBookingId(bookingId).get(0);
        assertThat(p.getPaymentLinkId()).isEqualTo(mintedLink);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void PSP_007_pspRejects_returns502_andLeavesRowPending() throws Exception {
        UUID bookingId = newBookingWithRoomLine();
        stubStatus = 409;
        stubBody = "{\"code\":\"DUPLICATE_MERCHANT_REFERENCE\",\"message\":\"already used\"}";

        mvc.perform(post("/bookings/" + bookingId + "/payments")
                        .header(HA, HA_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":18000}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PSP_ERROR"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("409")));

        // PSP-007: tx1 committed the PENDING row; tx2 never ran → paymentLinkId still null.
        List<Payment> rows = paymentRepository.findByBookingId(bookingId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(rows.get(0).getPaymentLinkId()).isNull();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UUID newBookingWithRoomLine() throws Exception {
        UUID customerId = UUID.fromString(node(mvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"PSP Out Test\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
        UUID productId = productService.createRoom(
                "PSP Room " + UUID.randomUUID(), 18000L, "GBP",
                "HIGH", "KING", 2, true, 5).getId();
        UUID bookingId = UUID.fromString(node(mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
        mvc.perform(post("/bookings/" + bookingId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"startsAt\":\"2026-08-01T15:00:00Z\","
                                + "\"endsAt\":\"2026-08-03T11:00:00Z\",\"quantity\":1}"))
                .andExpect(status().isCreated());
        return bookingId;
    }

    private JsonNode node(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
