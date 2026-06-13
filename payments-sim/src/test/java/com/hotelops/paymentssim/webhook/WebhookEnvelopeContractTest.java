package com.hotelops.paymentssim.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hotelops.paymentssim.domain.PspEventCode;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * WHK-002 / WAVE0_03 §3 — every emitted envelope carries the common fields, and each
 * event only adds the event-specific fields §3 demands (NON_NULL inclusion). Mirrors
 * the Spring Boot ObjectMapper config (jsr310 + ISO dates) so the asserted shape is
 * what {@link WebhookSender} actually puts on the wire.
 */
class WebhookEnvelopeContractTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static final String PSP = "PSP-aZ91kQ2mn0PdL3xR";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-12T14:03:21Z");

    private JsonNode serialize(WebhookEnvelope e) throws Exception {
        return mapper.readTree(mapper.writeValueAsBytes(e));
    }

    private void assertCommonFields(JsonNode n, PspEventCode code) {
        assertThat(n.get("eventId").asText()).startsWith("evt_");
        assertThat(n.get("eventCode").asText()).isEqualTo(code.name());
        assertThat(n.get("idempotencyKey").asText()).contains(":" + code.name() + ":");
        assertThat(n.has("merchantReference")).isTrue();
        assertThat(n.has("pspReference")).isTrue();
        assertThat(n.has("amount")).isTrue();
        assertThat(n.get("currency").asText()).isEqualTo("GBP");
        assertThat(n.get("occurredAt").asText()).isEqualTo("2026-06-12T14:03:21Z");
        assertThat(n.has("success")).isTrue();
    }

    @Test
    void authorisationCarriesAuthExpiresAtOnly() throws Exception {
        var e = new WebhookEnvelope("evt_a", PspEventCode.AUTHORISATION,
                PSP + ":AUTHORISATION:1", "MR-1", PSP, 70000L, "GBP", NOW, true,
                NOW.plusHours(168), null, null, null);
        JsonNode n = serialize(e);
        assertCommonFields(n, PspEventCode.AUTHORISATION);
        assertThat(n.has("authExpiresAt")).isTrue();
        assertThat(n.has("reason")).isFalse();
        assertThat(n.has("originalReference")).isFalse();
        assertThat(n.has("refundMerchantReference")).isFalse();
    }

    @Test
    void captureCarriesNoEventSpecificFields() throws Exception {
        var e = new WebhookEnvelope("evt_c", PspEventCode.CAPTURE,
                PSP + ":CAPTURE:1", "MR-1", PSP, 54000L, "GBP", NOW, true,
                null, null, null, null);
        JsonNode n = serialize(e);
        assertCommonFields(n, PspEventCode.CAPTURE);
        assertThat(n.get("amount").asLong()).isEqualTo(54000L);
        assertThat(n.has("authExpiresAt")).isFalse();
        assertThat(n.has("reason")).isFalse();
        assertThat(n.has("originalReference")).isFalse();
        assertThat(n.has("refundMerchantReference")).isFalse();
    }

    @Test
    void cancellationAmountIsZeroNoExtraFields() throws Exception {
        var e = new WebhookEnvelope("evt_x", PspEventCode.CANCELLATION,
                PSP + ":CANCELLATION:1", "MR-1", PSP, 0L, "GBP", NOW, true,
                null, null, null, null);
        JsonNode n = serialize(e);
        assertCommonFields(n, PspEventCode.CANCELLATION);
        assertThat(n.get("amount").asLong()).isZero();
        assertThat(n.has("authExpiresAt")).isFalse();
        assertThat(n.has("originalReference")).isFalse();
    }

    @Test
    void refundCarriesOriginalAndRefundMerchantReference() throws Exception {
        String refundPsp = "PSP-mn0PdL3xRaZ91kQ2";
        var e = new WebhookEnvelope("evt_r", PspEventCode.REFUND,
                refundPsp + ":REFUND:1", "MR-1", refundPsp, 6000L, "GBP", NOW, true,
                null, null, PSP, "MR-RF-9");
        JsonNode n = serialize(e);
        assertCommonFields(n, PspEventCode.REFUND);
        assertThat(n.get("originalReference").asText()).isEqualTo(PSP);
        assertThat(n.get("refundMerchantReference").asText()).isEqualTo("MR-RF-9");
        assertThat(n.has("authExpiresAt")).isFalse();
        assertThat(n.has("reason")).isFalse();
    }

    @Test
    void failedVariantCarriesSuccessFalseAndReason() throws Exception {
        var e = new WebhookEnvelope("evt_cf", PspEventCode.CAPTURE_FAILED,
                PSP + ":CAPTURE_FAILED:1", "MR-1", PSP, 54000L, "GBP", NOW, false,
                null, "insufficient_funds", null, null);
        JsonNode n = serialize(e);
        assertCommonFields(n, PspEventCode.CAPTURE_FAILED);
        assertThat(n.get("success").asBoolean()).isFalse();
        assertThat(n.get("reason").asText()).isEqualTo("insufficient_funds");
    }
}
