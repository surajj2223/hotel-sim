package com.hotelops.paymentssim.web.dto;

/** Standard error envelope — mirrors core-api's ApiError so PSP-007 can include the
 * underlying reason verbatim in the operator-facing 502. WAVE0_05 §2.5. */
public record ApiError(String code, String message) {}
