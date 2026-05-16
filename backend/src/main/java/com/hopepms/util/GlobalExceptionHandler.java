package com.hopepms.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException ex) {
        log.warn("Handled ApiException -> {} {}: {}", ex.status().value(), ex.code(), ex.getMessage());
        return ResponseEntity.status(ex.status())
                .body(Map.of("code", ex.code(), "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException ex) {
        log.warn("Bad input -> 400 validation: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("code", "validation", "message", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        log.error("Unhandled exception -> 500 server_error", ex);
        return ResponseEntity.internalServerError()
                .body(Map.of("code", "server_error", "message", "Unexpected error"));
    }
}
