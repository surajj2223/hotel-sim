package com.hotelops.paymentssim.domain;

import java.io.Serializable;
import java.util.Objects;

/** Composite-PK class for {@link PspEventSequence}. PSP-011 §4.3. */
public class PspEventSequenceId implements Serializable {

    private String pspReference;
    private String eventCode;

    public PspEventSequenceId() {}

    public PspEventSequenceId(String pspReference, String eventCode) {
        this.pspReference = pspReference;
        this.eventCode = eventCode;
    }

    public String getPspReference() {
        return pspReference;
    }

    public String getEventCode() {
        return eventCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PspEventSequenceId other)) return false;
        return Objects.equals(pspReference, other.pspReference)
                && Objects.equals(eventCode, other.eventCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pspReference, eventCode);
    }
}
