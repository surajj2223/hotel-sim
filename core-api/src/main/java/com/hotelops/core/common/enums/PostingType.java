package com.hotelops.core.common.enums;

/** ENM-008 — ledger posting classes. Auth is NOT a posting; only capture &amp; refunds. */
public enum PostingType {
    /** Capture posts positive revenue, attributed to a vertical. */
    REVENUE,
    /** Refund reverses revenue (negative), traceable via originalReference. */
    REFUND_REVERSAL
}
