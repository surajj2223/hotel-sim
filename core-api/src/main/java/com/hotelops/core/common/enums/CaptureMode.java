package com.hotelops.core.common.enums;

/** ENM-004 — per-payment capture mode, vertical-defaulted. */
public enum CaptureMode {
    /** Auth-and-capture together (e.g. F&B). */
    IMMEDIATE,
    /** Auth now, capture later (e.g. Rooms: hold card, capture at checkout). */
    MANUAL
}
