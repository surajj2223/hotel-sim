package com.hotelops.paymentssim.web;

import com.hotelops.paymentssim.common.error.AmountExceededException;
import com.hotelops.paymentssim.common.error.DuplicateAnchorException;
import com.hotelops.paymentssim.common.error.InvalidStateTransitionException;
import com.hotelops.paymentssim.common.error.PspApiKeyMissingException;
import com.hotelops.paymentssim.web.dto.ApiError;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps exceptions to the WAVE0_05 §2.5 error envelope. Mirrors core-api's
 * GlobalExceptionHandler in shape so the operator-facing 502 in core-api can pass
 * the upstream {@code code}/{@code message} through verbatim (PSP-007).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PspApiKeyMissingException.class)
    public ResponseEntity<ApiError> apiKeyMissing(PspApiKeyMissingException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("PSP_API_KEY_MISSING", ex.getMessage()));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> notFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(DuplicateAnchorException.class)
    public ResponseEntity<ApiError> duplicateAnchor(DuplicateAnchorException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ApiError> invalidState(InvalidStateTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(AmountExceededException.class)
    public ResponseEntity<ApiError> amountExceeded(AmountExceededException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError(ex.getCode(), ex.getMessage()));
    }

    /**
     * Race-window fallback (§6.3 flag): if two writers concurrently pass the lookup
     * check, the second hits the UNIQUE constraint at flush time. Map to 409 so the
     * caller sees the same outcome as the lookup path.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> dataIntegrity(DataIntegrityViolationException ex) {
        String msg = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("DUPLICATE_ANCHOR", "Concurrent duplicate anchor: " + msg));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> bodyValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("Request validation failed");
        return badRequest(detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> paramValidation(ConstraintViolationException ex) {
        return badRequest(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("Invalid value for '" + ex.getName() + "'");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> illegalArgument(IllegalArgumentException ex) {
        return badRequest(ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex) {
        return badRequest("Malformed or missing request body");
    }

    private ResponseEntity<ApiError> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("BAD_REQUEST", message));
    }
}
