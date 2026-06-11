package com.hotelops.core.web;

import com.hotelops.core.common.error.HumanAuthRequiredException;
import com.hotelops.core.common.error.StateChangedException;
import com.hotelops.core.web.dto.ApiError;
import com.hotelops.core.web.dto.StateConflict;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * Maps domain/validation exceptions to the frozen error envelopes (WAVE0_02_OPENAPI.yaml):
 *   EntityNotFoundException        -> 404 ApiError
 *   StateChangedException (INV-003)-> 409 StateConflict (currentState carries availability)
 *   HumanAuthRequiredException (INV-007) -> 428 ApiError
 *   any request-validation failure -> 400 ApiError
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 404 — a referenced resource (customer/product/booking) does not exist. */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> notFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", ex.getMessage()));
    }

    /**
     * 409 — write-time revalidation found state had moved (INV-003). The body carries the
     * current truth so the caller can re-read and retry; Stage 1's revalidation moves
     * availability, rendered as {@code {"availableUnits": <n>}}.
     */
    @ExceptionHandler(StateChangedException.class)
    public ResponseEntity<StateConflict> stateConflict(StateChangedException ex) {
        Object current = ex.getCurrentState();
        Object currentState = (current == null) ? Map.of() : Map.of("availableUnits", current);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new StateConflict("STATE_CONFLICT", ex.getMessage(), currentState));
    }

    /**
     * 428 — INV-007 repercussive write attempted without an {@code X-Human-Auth} signal.
     * The exception also carries {@code @ResponseStatus(PRECONDITION_REQUIRED)} as a
     * defensive default, but this handler ensures the body is the spec's {@code ApiError}
     * envelope rather than Spring's default error structure.
     */
    @ExceptionHandler(HumanAuthRequiredException.class)
    public ResponseEntity<ApiError> humanAuthRequired(HumanAuthRequiredException ex) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                .body(new ApiError("HUMAN_AUTH_REQUIRED", ex.getMessage()));
    }

    /** 400 — @Valid request body failed bean validation. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> bodyValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("Request validation failed");
        return badRequest(detail);
    }

    /** 400 — constrained method parameter (e.g. quantity >= 1) failed validation. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> paramValidation(ConstraintViolationException ex) {
        return badRequest(ex.getMessage());
    }

    /** 400 — native MVC method validation (constrained @RequestParam) failed. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> methodValidation(HandlerMethodValidationException ex) {
        return badRequest("Request validation failed");
    }

    /** 400 — a path/query value could not be converted (bad uuid, enum, or date-time). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("Invalid value for '" + ex.getName() + "'");
    }

    /** 400 — unsupported argument (e.g. a vertical other than ROOM in Stage 1). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> illegalArgument(IllegalArgumentException ex) {
        return badRequest(ex.getMessage());
    }

    /** 400 — request body was missing or malformed JSON. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex) {
        return badRequest("Malformed or missing request body");
    }

    private ResponseEntity<ApiError> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("BAD_REQUEST", message));
    }
}
