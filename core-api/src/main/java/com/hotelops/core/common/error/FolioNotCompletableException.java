package com.hotelops.core.common.error;

import com.hotelops.core.common.enums.BookingStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;
import java.util.UUID;

/**
 * API-015 — thrown when {@code completeFolio} write-time revalidation fails loudly without
 * writing: C1 (a non-CANCELLED line is not COMPLETED) and/or C2 ({@code customerOwes != 0},
 * RX-003), or the folio is in a terminal/ineligible state (CANCELLED or PENDING).
 *
 * Maps to HTTP 409 Conflict ({@code FolioCompletionConflict}); carries the live state so the
 * advice can render the {@code currentState} body. Holds only domain values (no web.dto
 * coupling), mirroring {@link StateChangedException}.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class FolioNotCompletableException extends RuntimeException {

    private final BookingStatus status;
    private final long customerOwes;
    private final List<UUID> incompleteLineIds;

    public FolioNotCompletableException(String message, BookingStatus status,
                                        long customerOwes, List<UUID> incompleteLineIds) {
        super(message);
        this.status = status;
        this.customerOwes = customerOwes;
        this.incompleteLineIds = List.copyOf(incompleteLineIds);
    }

    public BookingStatus getStatus() {
        return status;
    }

    public long getCustomerOwes() {
        return customerOwes;
    }

    public List<UUID> getIncompleteLineIds() {
        return incompleteLineIds;
    }
}
