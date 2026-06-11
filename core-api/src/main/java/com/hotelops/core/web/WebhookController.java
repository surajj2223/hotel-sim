package com.hotelops.core.web;

import com.hotelops.core.common.error.InvalidPspSignatureException;
import com.hotelops.core.payment.webhook.WebhookService;
import com.hotelops.core.web.dto.PspWebhookEvent;
import com.hotelops.core.web.dto.WebhookAck;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API-013 — inbound PSP webhook receiver. Idempotent; signature-authenticated (WHK-014).
 *
 * The receiver is machine-to-machine (PSP → core-api). It is <b>not</b> human-gated;
 * operator writes (capture/refund/cancel) are the human-gated calls that <i>trigger</i>
 * the PSP, which then asynchronously hits this endpoint.
 */
@RestController
@RequestMapping("/webhooks/psp")
public class WebhookController {

    /** Header carrying the HMAC of the raw body — WHK-014. */
    static final String SIGNATURE_HEADER = "X-PSP-Signature";

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping
    public WebhookAck receive(
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @Valid @RequestBody PspWebhookEvent event) {
        // WHK-014: signature presence-check only this feature.
        // Feature 2: real HMAC verification using the shared PSP secret.
        if (signature == null || signature.isBlank()) {
            throw new InvalidPspSignatureException("Missing or invalid " + SIGNATURE_HEADER);
        }
        return webhookService.process(event);
    }
}
