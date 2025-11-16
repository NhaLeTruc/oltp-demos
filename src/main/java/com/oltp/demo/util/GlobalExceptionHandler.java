package com.oltp.demo.util;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Global exception handler for REST controllers.
 *
 * Provides consistent error responses across all endpoints and
 * implements proper observability for error scenarios.
 *
 * Key features:
 * - Structured error responses with correlation IDs
 * - Appropriate HTTP status codes per error type
 * - Error logging with context for debugging
 * - Metrics recording for error rates
 * - Security: No stack traces in production responses
 *
 * Error response format:
 * <pre>
 * {
 *   "timestamp": "2025-11-16T10:30:00Z",
 *   "status": 409,
 *   "error": "Conflict",
 *   "message": "Optimistic lock exception: Account was modified by another transaction",
 *   "path": "/api/demos/concurrency/optimistic-locking",
 *   "correlationId": "550e8400-e29b-41d4-a716-446655440000"
 * }
 * </pre>
 *
 * @see com.oltp.demo.util.CorrelationIdFilter
 * @see com.oltp.demo.util.MetricsHelper
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MetricsHelper metricsHelper;

    /**
     * Handles optimistic locking exceptions.
     *
     * Occurs when two transactions try to update the same entity concurrently
     * and the version check fails.
     *
     * HTTP 409 Conflict - indicates concurrent modification
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return error response with HTTP 409
     */
    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLockException(
            OptimisticLockException ex,
            HttpServletRequest request) {

        log.warn("Optimistic lock exception: {}", ex.getMessage());
        metricsHelper.recordOptimisticLockException();

        Map<String, Object> body = createErrorBody(
            HttpStatus.CONFLICT,
            "Optimistic lock exception: Entity was modified by another transaction. Please retry.",
            request.getRequestURI()
        );

        return new ResponseEntity<>(body, HttpStatus.CONFLICT);
    }

    /**
     * Handles pessimistic locking exceptions.
     *
     * Occurs when a pessimistic lock cannot be acquired (e.g., timeout).
     *
     * HTTP 409 Conflict - indicates lock contention
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return error response with HTTP 409
     */
    @ExceptionHandler(PessimisticLockException.class)
    public ResponseEntity<Map<String, Object>> handlePessimisticLockException(
            PessimisticLockException ex,
            HttpServletRequest request) {

        log.warn("Pessimistic lock exception: {}", ex.getMessage());

        Map<String, Object> body = createErrorBody(
            HttpStatus.CONFLICT,
            "Pessimistic lock exception: Could not acquire lock. Please retry.",
            request.getRequestURI()
        );

        return new ResponseEntity<>(body, HttpStatus.CONFLICT);
    }

    /**
     * Handles data integrity violations.
     *
     * Occurs when database constraints are violated (CHECK, FK, UNIQUE, etc.).
     *
     * HTTP 400 Bad Request - indicates client error
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return error response with HTTP 400
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(
            DataIntegrityViolationException ex,
            HttpServletRequest request) {

        log.warn("Data integrity violation: {}", ex.getMessage());

        // Extract constraint name if possible
        String message = extractConstraintMessage(ex);

        Map<String, Object> body = createErrorBody(
            HttpStatus.BAD_REQUEST,
            "Data integrity violation: " + message,
            request.getRequestURI()
        );

        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles validation errors (e.g., @Valid annotation failures).
     *
     * HTTP 400 Bad Request - indicates invalid input
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return error response with HTTP 400
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        log.warn("Validation exception: {}", ex.getMessage());

        // Collect all validation errors
        StringBuilder message = new StringBuilder("Validation failed: ");
        ex.getBindingResult().getFieldErrors().forEach(error ->
            message.append(error.getField())
                   .append(" ")
                   .append(error.getDefaultMessage())
                   .append("; ")
        );

        Map<String, Object> body = createErrorBody(
            HttpStatus.BAD_REQUEST,
            message.toString(),
            request.getRequestURI()
        );

        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles illegal argument exceptions.
     *
     * Thrown by business logic when invalid arguments are provided.
     *
     * HTTP 400 Bad Request - indicates client error
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return error response with HTTP 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(
            IllegalArgumentException ex,
            HttpServletRequest request) {

        log.warn("Illegal argument: {}", ex.getMessage());

        Map<String, Object> body = createErrorBody(
            HttpStatus.BAD_REQUEST,
            ex.getMessage(),
            request.getRequestURI()
        );

        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles all other uncaught exceptions.
     *
     * HTTP 500 Internal Server Error - indicates server-side error
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return error response with HTTP 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(
            Exception ex,
            HttpServletRequest request) {

        log.error("Unhandled exception", ex);

        Map<String, Object> body = createErrorBody(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Please contact support with correlation ID.",
            request.getRequestURI()
        );

        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Creates a standardized error response body.
     *
     * @param status the HTTP status
     * @param message the error message
     * @param path the request path
     * @return error response map
     */
    private Map<String, Object> createErrorBody(HttpStatus status, String message, String path) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", path);
        body.put("correlationId", CorrelationIdFilter.getCurrentCorrelationId());

        return body;
    }

    /**
     * Extracts a user-friendly message from DataIntegrityViolationException.
     *
     * Attempts to identify the violated constraint and provide context.
     *
     * @param ex the exception
     * @return user-friendly error message
     */
    private String extractConstraintMessage(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause().getMessage();

        // Common constraint patterns
        if (message.contains("chk_accounts_balance_non_negative")) {
            return "Account balance cannot be negative";
        } else if (message.contains("chk_transactions_amount_positive")) {
            return "Transaction amount must be positive";
        } else if (message.contains("fk_")) {
            return "Referenced entity does not exist";
        } else if (message.contains("unique") || message.contains("duplicate")) {
            return "Duplicate value for unique field";
        }

        return "Constraint violation";
    }
}
