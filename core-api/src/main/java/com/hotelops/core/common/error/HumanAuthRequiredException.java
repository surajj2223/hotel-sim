package com.hotelops.core.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a repercussive write is attempted without the required human-authorisation
 * signal.  Maps to HTTP 428 Precondition Required.
 * Enforces INV-007: human-authorisation gate.
 */
@ResponseStatus(HttpStatus.PRECONDITION_REQUIRED)
public class HumanAuthRequiredException extends RuntimeException {

    public HumanAuthRequiredException(String operation) {
        super("Human authorisation signal required for: " + operation);
    }
}
