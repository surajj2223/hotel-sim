package com.hotelops.paymentssim.web.dto;

import jakarta.validation.constraints.Positive;

/**
 * WAVE0_05 §5.1 — body for {@code POST /v1/test/payment-links/{linkId}/authorise}.
 * Both fields optional; {@code amount} overrides the persisted {@code amount_requested}.
 */
public record AuthoriseTriggerRequest(@Positive Long amount) {
}
