package com.oltp.demo.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oltp.demo.service.failure.CircuitBreakerService;
import com.oltp.demo.service.failure.RecoveryDemoService;
import com.oltp.demo.service.failure.RetryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for failure scenario and recovery demonstrations.
 *
 * Provides endpoints to demonstrate resilience patterns:
 * - Retry with exponential backoff
 * - Circuit breaker pattern
 * - Crash recovery and durability verification
 * - Connection pool exhaustion handling
 *
 * All endpoints return JSON responses with demonstration results.
 * Correlation IDs are automatically added to responses for tracing.
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US5: Failure Scenarios and Recovery</a>
 */
@Slf4j
@RestController
@RequestMapping("/api/demos/failure")
@RequiredArgsConstructor
@Tag(name = "Failure Demonstrations", description = "Demonstrations of failure handling and recovery patterns")
public class FailureDemoController {

    private final RetryService retryService;
    private final CircuitBreakerService circuitBreakerService;
    private final RecoveryDemoService recoveryDemoService;

    // =========================================================================
    // Retry Demonstrations (T186)
    // =========================================================================

    /**
     * Demonstrates retry with exponential backoff.
     *
     * Shows how the system automatically retries transient failures
     * with increasing delays between attempts.
     *
     * Query Parameters:
     * - simulateFailure: If true, simulates transient failures (default: false)
     * - fromAccountId: Source account ID (required)
     * - toAccountId: Destination account ID (required)
     * - amount: Transfer amount (required)
     *
     * Response includes:
     * - success: Whether transfer succeeded
     * - attempts: Number of retry attempts made
     * - transactionId: ID of successful transaction (if success=true)
     * - duration: Time spent including retries
     * - fallbackUsed: Whether fallback logic was triggered
     */
    @PostMapping("/retry")
    @Operation(summary = "Demonstrate retry with exponential backoff",
               description = "Executes a transfer with automatic retry on transient failures")
    public ResponseEntity<RetryService.RetryResult> demonstrateRetry(
            @RequestBody RetryRequest request) {

        log.info("API: Retry demo - from={}, to={}, amount={}, simulateFailure={}",
                request.fromAccountId, request.toAccountId, request.amount, request.simulateFailure);

        try {
            RetryService.RetryResult result = retryService.performTransferWithRetry(
                request.fromAccountId,
                request.toAccountId,
                request.amount,
                request.simulateFailure
            );

            if (result.isSuccess()) {
                return ResponseEntity.ok(result);
            } else {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
            }

        } catch (Exception e) {
            log.error("Retry demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(RetryService.RetryResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build());
        }
    }

    /**
     * Tests database connection with retry logic.
     *
     * Useful for verifying database availability and connection pool health.
     */
    @GetMapping("/retry/connection-test")
    @Operation(summary = "Test database connection with retry",
               description = "Attempts to connect to database with automatic retry")
    public ResponseEntity<RetryService.RetryResult> testConnectionWithRetry() {
        log.info("API: Connection test with retry");

        try {
            RetryService.RetryResult result = retryService.testConnectionWithRetry();

            if (result.isSuccess()) {
                return ResponseEntity.ok(result);
            } else {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
            }

        } catch (Exception e) {
            log.error("Connection test failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(RetryService.RetryResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build());
        }
    }

    /**
     * Gets retry metrics.
     *
     * Returns comprehensive retry statistics including:
     * - Total attempts
     * - Success/failure counts
     * - Fallback usage
     * - Success rate percentage
     */
    @GetMapping("/retry/metrics")
    @Operation(summary = "Get retry metrics",
               description = "Returns retry statistics for monitoring")
    public ResponseEntity<Map<String, Object>> getRetryMetrics() {
        log.info("API: Get retry metrics");
        Map<String, Object> metrics = retryService.getRetryMetrics();
        return ResponseEntity.ok(metrics);
    }

    /**
     * Resets retry metrics.
     *
     * Useful for testing and demonstrations to start with clean metrics.
     */
    @PostMapping("/retry/reset")
    @Operation(summary = "Reset retry metrics",
               description = "Resets all retry counters to zero")
    public ResponseEntity<Void> resetRetryMetrics() {
        log.info("API: Reset retry metrics");
        retryService.resetMetrics();
        return ResponseEntity.ok().build();
    }

    // =========================================================================
    // Circuit Breaker Demonstrations (T187)
    // =========================================================================

    /**
     * Demonstrates circuit breaker pattern.
     *
     * Shows how the circuit breaker prevents cascading failures by
     * temporarily blocking requests to a failing service.
     *
     * Circuit Breaker States:
     * - CLOSED: Normal operation, requests flow through
     * - OPEN: Fail fast, block all requests
     * - HALF_OPEN: Test if service recovered
     *
     * Request Body:
     * - fromAccountId: Source account ID (required)
     * - toAccountId: Destination account ID (required)
     * - amount: Transfer amount (required)
     * - simulateFailure: If true, simulates failures to trigger circuit opening
     *
     * Response includes:
     * - success: Whether transfer succeeded
     * - stateBefore: Circuit state before operation
     * - stateAfter: Circuit state after operation
     * - rejected: Whether request was rejected due to OPEN circuit
     */
    @PostMapping("/circuit-breaker")
    @Operation(summary = "Demonstrate circuit breaker pattern",
               description = "Executes a transfer protected by circuit breaker")
    public ResponseEntity<CircuitBreakerService.CircuitBreakerResult> demonstrateCircuitBreaker(
            @RequestBody CircuitBreakerRequest request) {

        log.info("API: Circuit breaker demo - from={}, to={}, amount={}, simulateFailure={}",
                request.fromAccountId, request.toAccountId, request.amount, request.simulateFailure);

        CircuitBreakerService.CircuitBreakerResult result =
            circuitBreakerService.performTransferWithCircuitBreaker(
                request.fromAccountId,
                request.toAccountId,
                request.amount,
                request.simulateFailure
            );

        if (result.isSuccess()) {
            return ResponseEntity.ok(result);
        } else if (result.isRejected()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    /**
     * Gets circuit breaker metrics and current state.
     *
     * Returns comprehensive metrics including:
     * - Current state (CLOSED, OPEN, HALF_OPEN)
     * - Failure rate percentage
     * - Slow call rate percentage
     * - Number of successful/failed/rejected calls
     */
    @GetMapping("/circuit-breaker/metrics")
    @Operation(summary = "Get circuit breaker metrics",
               description = "Returns circuit breaker state and statistics")
    public ResponseEntity<Map<String, Object>> getCircuitBreakerMetrics() {
        log.info("API: Get circuit breaker metrics");
        Map<String, Object> metrics = circuitBreakerService.getCircuitBreakerMetrics();
        return ResponseEntity.ok(metrics);
    }

    /**
     * Manually transitions circuit breaker state.
     *
     * Useful for testing and demonstrations.
     *
     * Query Parameters:
     * - state: Target state (CLOSED, OPEN, HALF_OPEN)
     */
    @PostMapping("/circuit-breaker/transition")
    @Operation(summary = "Manually transition circuit breaker state",
               description = "Forces circuit breaker to specific state for testing")
    public ResponseEntity<Map<String, String>> transitionCircuitBreaker(
            @RequestParam String state) {

        log.info("API: Transition circuit breaker to state={}", state);

        try {
            switch (state.toUpperCase()) {
                case "CLOSED":
                    circuitBreakerService.transitionToClosedState();
                    break;
                case "OPEN":
                    circuitBreakerService.transitionToOpenState();
                    break;
                case "HALF_OPEN":
                    circuitBreakerService.transitionToHalfOpenState();
                    break;
                default:
                    return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid state: " + state +
                            ". Valid states: CLOSED, OPEN, HALF_OPEN"));
            }

            return ResponseEntity.ok(Map.of(
                "message", "Circuit breaker transitioned to " + state,
                "currentState", circuitBreakerService.getCircuitBreakerMetrics().get("state").toString()
            ));

        } catch (Exception e) {
            log.error("Failed to transition circuit breaker: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Resets circuit breaker to initial state.
     *
     * Clears all metrics and transitions to CLOSED state.
     */
    @PostMapping("/circuit-breaker/reset")
    @Operation(summary = "Reset circuit breaker",
               description = "Resets circuit breaker to initial CLOSED state")
    public ResponseEntity<Void> resetCircuitBreaker() {
        log.info("API: Reset circuit breaker");
        circuitBreakerService.reset();
        return ResponseEntity.ok().build();
    }

    // =========================================================================
    // Recovery Demonstrations (T188)
    // =========================================================================

    /**
     * Verifies committed transactions after database crash/restart.
     *
     * Demonstrates ACID Durability by verifying that committed transactions
     * survive database crashes and restarts.
     *
     * Query Parameters:
     * - correlationId: Correlation ID of transactions to verify (required)
     *
     * Response includes:
     * - verified: Whether all expected transactions were found
     * - transactionCount: Number of transactions found
     * - committedCount: Number of committed transactions
     * - transferLogCount: Number of audit log entries
     */
    @GetMapping("/recovery/verify")
    @Operation(summary = "Verify committed transactions after crash",
               description = "Checks that committed transactions survived database restart")
    public ResponseEntity<RecoveryDemoService.RecoveryResult> verifyCommittedTransactions(
            @RequestParam String correlationId) {

        log.info("API: Verify committed transactions - correlationId={}", correlationId);

        RecoveryDemoService.RecoveryResult result =
            recoveryDemoService.verifyCommittedTransactions(correlationId);

        return ResponseEntity.ok(result);
    }

    /**
     * Demonstrates Write-Ahead Logging (WAL) verification.
     *
     * Shows PostgreSQL WAL configuration and current state.
     * WAL ensures durability by writing changes to log before committing.
     *
     * Response includes:
     * - walEnabled: Whether WAL is enabled
     * - currentWalLsn: Current WAL Log Sequence Number
     * - lastCheckpointLsn: Last checkpoint location
     * - inRecovery: Whether database is currently recovering
     * - walInfo: Detailed WAL configuration
     */
    @GetMapping("/recovery/wal")
    @Operation(summary = "Verify WAL configuration",
               description = "Shows PostgreSQL Write-Ahead Logging configuration")
    public ResponseEntity<RecoveryDemoService.WalResult> verifyWal() {
        log.info("API: Verify WAL configuration");

        RecoveryDemoService.WalResult result = recoveryDemoService.verifyWalConfiguration();

        return ResponseEntity.ok(result);
    }

    /**
     * Demonstrates point-in-time recovery query.
     *
     * Queries transactions within a specific time range,
     * useful for recovery planning and auditing.
     *
     * Query Parameters:
     * - startTime: Start of time range (ISO-8601 format, required)
     * - endTime: End of time range (ISO-8601 format, required)
     *
     * Response includes:
     * - transactionCount: Number of transactions in range
     * - successCount: Number of successful transactions
     * - totalAmount: Sum of transaction amounts
     * - transactions: List of transactions
     */
    @GetMapping("/recovery/point-in-time")
    @Operation(summary = "Query transactions in time range",
               description = "Shows transactions for point-in-time recovery planning")
    public ResponseEntity<RecoveryDemoService.PointInTimeResult> queryPointInTime(
            @RequestParam String startTime,
            @RequestParam String endTime) {

        log.info("API: Point-in-time query - start={}, end={}", startTime, endTime);

        try {
            Instant start = Instant.parse(startTime);
            Instant end = Instant.parse(endTime);

            RecoveryDemoService.PointInTimeResult result =
                recoveryDemoService.queryTransactionsInTimeRange(start, end);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Point-in-time query failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Gets database recovery statistics.
     *
     * Shows database uptime, transaction statistics, and checkpoint info.
     *
     * Response includes:
     * - databaseStartTime: When database last started
     * - uptimeSeconds: Seconds since last restart
     * - transactionStats: Commit/rollback counts
     * - checkpointStats: Checkpoint statistics
     */
    @GetMapping("/recovery/statistics")
    @Operation(summary = "Get database recovery statistics",
               description = "Shows database uptime and recovery statistics")
    public ResponseEntity<Map<String, Object>> getRecoveryStatistics() {
        log.info("API: Get recovery statistics");

        Map<String, Object> stats = recoveryDemoService.getRecoveryStatistics();

        return ResponseEntity.ok(stats);
    }

    // =========================================================================
    // Request/Response DTOs
    // =========================================================================

    /**
     * Request DTO for retry demonstrations.
     */
    public static class RetryRequest {
        public Long fromAccountId;
        public Long toAccountId;
        public BigDecimal amount;
        public boolean simulateFailure = false;
    }

    /**
     * Request DTO for circuit breaker demonstrations.
     */
    public static class CircuitBreakerRequest {
        public Long fromAccountId;
        public Long toAccountId;
        public BigDecimal amount;
        public boolean simulateFailure = false;
    }
}
