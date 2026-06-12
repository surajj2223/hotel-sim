package com.hotelops.paymentssim.web.dto;

import jakarta.validation.constraints.Positive;

/** PSP-002 / WAVE0_05 §2.2 request body. {@code amount} null = full capture. */
public record CaptureRequest(@Positive Long amount) {}
