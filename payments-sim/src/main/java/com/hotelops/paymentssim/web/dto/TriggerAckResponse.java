package com.hotelops.paymentssim.web.dto;

/**
 * Generic ACK body for capture/cancel/refund test triggers. Echoes the reference and
 * the {@code eventCode} that was driven, so a sync-mode caller sees what landed.
 */
public record TriggerAckResponse(String pspReference, String eventCode) {
}
