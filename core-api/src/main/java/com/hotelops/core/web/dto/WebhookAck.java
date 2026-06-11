package com.hotelops.core.web.dto;

/**
 * API-013 response body — WebhookAck (WAVE0_02_OPENAPI.yaml).
 *
 * {@code duplicate} is true when an inbound event's {@code idempotencyKey} already
 * exists in {@code webhook_inbox} (WHK-005) — same 200, no second effect.
 */
public record WebhookAck(
        boolean received,
        Boolean duplicate
) {
}
