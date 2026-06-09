package com.hotelops.core.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a write detects that state moved since the caller's last read.
 * Maps to HTTP 409 Conflict — callers must re-read current state before retrying.
 * Enforces INV-003: write-time revalidation.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class StateChangedException extends RuntimeException {

    private final Object currentState;

    public StateChangedException(String message, Object currentState) {
        super(message);
        this.currentState = currentState;
    }

    public Object getCurrentState() {
        return currentState;
    }
}
