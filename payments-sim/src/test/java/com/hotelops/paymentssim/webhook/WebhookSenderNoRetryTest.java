package com.hotelops.paymentssim.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hotelops.paymentssim.domain.PspEventCode;
import java.net.ServerSocket;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * PSP-007 / PSP-008 / Trap D — outbound webhook delivery is single-attempt, fail-loud,
 * NO retry. A non-2xx is reported (not retried); a connection failure returns
 * {@code delivered=false} without throwing. There is no {@code RetryTemplate},
 * circuit-breaker, or DLQ anywhere in the path.
 */
class WebhookSenderNoRetryTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final WebhookSender sender = new WebhookSender(new HmacSigner("test-secret"), mapper);

    private WebhookEnvelope sample() {
        return new WebhookEnvelope("evt_1", PspEventCode.CAPTURE, "PSP-x:CAPTURE:1",
                "MR-1", "PSP-x", 54000L, "GBP", OffsetDateTime.now(), true,
                null, null, null, null);
    }

    @Test
    void non2xxIsReportedExactlyOnceNeverRetried() throws Exception {
        try (var receiver = new RecordingWebhookReceiver(500)) {
            var result = sender.send(sample(), receiver.url());

            assertThat(result.delivered()).isFalse();
            assertThat(result.statusCode()).isEqualTo(500);
            // Exactly one HTTP attempt reached the receiver — no retry tick.
            assertThat(receiver.requestCount.get()).isEqualTo(1);
        }
    }

    @Test
    void connectionRefusedFailsLoudWithoutThrowing() throws Exception {
        // Bind then immediately release a port so nothing is listening → connection refused.
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        String deadUrl = "http://127.0.0.1:" + deadPort + "/webhooks/psp";

        var ref = new java.util.concurrent.atomic.AtomicReference<WebhookSender.DispatchResult>();
        assertThatCode(() -> ref.set(sender.send(sample(), deadUrl))).doesNotThrowAnyException();
        assertThat(ref.get().delivered()).isFalse();
    }
}
