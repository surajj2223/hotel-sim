package com.hotelops.core.payment.psp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Outbound PSP-001 response (WAVE0_05 §2.1). {@code core-api} only needs the minted
 * {@code paymentLinkId} to stamp; other fields (merchantReference/shopperReference/amount/
 * currency/status/hostedUrl) are echoed and ignored here. {@code JsonIgnoreProperties} guards
 * against the contract growing fields without breaking the client.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PspPaymentLinkResponse(
        String paymentLinkId,
        String status
) {
}
