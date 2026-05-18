package com.hopepms.util;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException ex) {
        log.warn("Handled ApiException -> {} {}: {}", ex.status().value(), ex.code(), ex.getMessage());
        return ResponseEntity.status(ex.status())
                .body(Map.of("code", ex.code(), "message", ex.getMessage()));
    }

    /**
     * Bean Validation failure on an @Valid @RequestBody (e.g. a product code
     * that isn't two-letters-four-digits). Previously this fell through to
     * handleGeneric and surfaced to the UI as an opaque 500 "Unexpected
     * error", which is exactly what blocked Add Product.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleBeanValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .distinct()
                .collect(Collectors.joining("; "));
        if (detail.isBlank()) detail = "Some fields are invalid.";
        log.warn("Validation failed -> 400 validation: {}", detail);
        return ResponseEntity.badRequest()
                .body(Map.of("code", "validation", "message", detail));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleConstraint(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        if (detail.isBlank()) detail = "Request failed validation.";
        log.warn("Constraint violation -> 400 validation: {}", detail);
        return ResponseEntity.badRequest()
                .body(Map.of("code", "validation", "message", detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body -> 400 validation");
        return ResponseEntity.badRequest()
                .body(Map.of("code", "validation", "message", "Request body is missing or malformed."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException ex) {
        log.warn("Bad input -> 400 validation: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("code", "validation", "message", ex.getMessage()));
    }

    /**
     * A row already exists for a unique key — e.g. POST /api/products with a
     * prodCode that's already in the catalogue, or a duplicate user. Without
     * this, Spring's DuplicateKeyException fell through to handleGeneric and
     * the UI showed an opaque 500 "Unexpected error" instead of telling the
     * operator the record already exists. Returns 409 so the client can show
     * a precise, actionable message.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, String>> handleDuplicate(DuplicateKeyException ex) {
        log.warn("Duplicate key -> 409 conflict: {}", rootMessage(ex));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "conflict",
                        "message", "That record already exists. Use a different code or value."));
    }

    /**
     * Any other database-layer failure (constraint violation, value too long,
     * deadlock, connection loss, …). Previously every one of these surfaced
     * as the catch-all "Unexpected error" with no hint of what went wrong;
     * the add-user/add-product outages were exactly this. We still keep the
     * detail server-side only, but return a clearer, non-leaking message and
     * the dedicated `db_error` code so the client can react.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccess(DataAccessException ex) {
        log.error("Database error -> 500 db_error: {}", rootMessage(ex), ex);
        return ResponseEntity.internalServerError()
                .body(Map.of("code", "db_error",
                        "message", "The database rejected this request. "
                                + "Check the submitted values and try again."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        log.error("Unhandled exception -> 500 server_error", ex);
        return ResponseEntity.internalServerError()
                .body(Map.of("code", "server_error", "message", "Unexpected error"));
    }

    /** Innermost cause message — the part that actually says what failed. */
    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static String describe(FieldError fe) {
        String field = fe.getField();
        return switch (field) {
            case "prodCode" -> "Product code must be two letters followed by four digits (e.g. AK0001).";
            case "description" -> "Description is required and must be 30 characters or fewer.";
            case "unit" -> "Unit must be one of pc, ea, mtr, pkg, ltr.";
            case "unitPrice" -> "Unit price must be a positive amount.";
            default -> field + " " + (fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage());
        };
    }
}
