package com.hotelops.core.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.core.TestcontainersConfiguration;
import com.hotelops.core.common.enums.PaymentStatus;
import com.hotelops.core.payment.psp.PspGatewayException;
import com.hotelops.core.product.ProductService;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PSP-006 / Trap A — the outbound HTTP call sits OUTSIDE any open transaction, so a failed
 * call leaves the {@code PENDING} payment row committed (tx1) with no link stamped (tx2
 * never ran). Points {@link com.hotelops.core.payment.psp.PspGateway} at a guaranteed-dead
 * port ("stopped payments-sim") and asserts the row survives as {@code PENDING}.
 *
 * <p>The decisive proof: after {@link PaymentOrchestrator#createPaymentLink} throws, a
 * fresh repository read still finds exactly one {@code PENDING} row. If the orchestration
 * were a single transaction (the class-level {@code @Transactional} this work removed), the
 * row would have rolled back and the read would be empty.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentOrchestratorTxOrderingTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping: no container runtime available.");
        }
    }

    @DynamicPropertySource
    static void deadPsp(DynamicPropertyRegistry registry) throws IOException {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();   // closed on exit → connection refused
        }
        registry.add("payments-sim.base-url", () -> "http://127.0.0.1:" + deadPort);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService productService;
    @Autowired PaymentOrchestrator orchestrator;
    @Autowired PaymentRepository paymentRepository;

    @Test
    void createPaymentLink_pspUnreachable_rowStaysPending_noTxHeld() throws Exception {
        UUID bookingId = newBookingWithRoomLine();

        assertThatThrownBy(() -> orchestrator.createPaymentLink(bookingId, 18000L, "GBP", null))
                .isInstanceOf(PspGatewayException.class);

        // tx1 committed the PENDING row independently of the (failed) network step.
        List<Payment> rows = paymentRepository.findByBookingId(bookingId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(rows.get(0).getPaymentLinkId()).as("tx2 never ran").isNull();

        // The orchestrator is not transactional — no tx is held across the HTTP call.
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private UUID newBookingWithRoomLine() throws Exception {
        UUID customerId = UUID.fromString(node(mvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Tx Order Test\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
        UUID productId = productService.createRoom(
                "Tx Room " + UUID.randomUUID(), 18000L, "GBP",
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
