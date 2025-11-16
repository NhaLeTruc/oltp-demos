package com.oltp.demo.service.failure;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oltp.demo.domain.Account;
import com.oltp.demo.domain.Transaction;
import com.oltp.demo.repository.AccountRepository;
import com.oltp.demo.repository.TransactionRepository;
import com.oltp.demo.util.CorrelationIdFilter;
import com.oltp.demo.util.MetricsHelper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating retry logic with exponential backoff.
 *
 * Demonstrations:
 * 1. Connection retry with exponential backoff using Spring Retry
 * 2. Retry metrics tracking (attempts, successes, failures)
 * 3. Max retry limit and fallback behavior
 * 4. Transient failure handling vs permanent failure detection
 *
 * Retry Strategy:
 * - Max attempts: 5
 * - Initial delay: 100ms
 * - Backoff multiplier: 2.0 (exponential)
 * - Max delay: 5000ms
 * - Retryable exceptions: TransientDataAccessException, QueryTimeoutException
 *
 * Metrics Tracked:
 * - retry.attempts.total: Total retry attempts across all operations
 * - retry.success.total: Operations that succeeded (with or without retries)
 * - retry.failure.total: Operations that failed after all retries
 * - retry.fallback.total: Operations that used fallback logic
 * - retry.duration: Time spent in retry operations
 *
 * Constitution.md alignment:
 * - Principle III: Resilience & Recovery - Systems must gracefully handle failures
 * - Principle IV: Observability - Track retry behavior for debugging
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US5: Failure Scenarios and Recovery</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetryService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final DataSource dataSource;
    private final MetricsHelper metricsHelper;

    // Retry metrics counters
    private final AtomicInteger retryAttemptsCounter = new AtomicInteger(0);
    private final AtomicInteger retrySuccessCounter = new AtomicInteger(0);
    private final AtomicInteger retryFailureCounter = new AtomicInteger(0);
    private final AtomicInteger fallbackCounter = new AtomicInteger(0);

    /**
     * Performs a transfer with automatic retry on transient failures.
     *
     * Uses Spring Retry's @Retryable annotation with exponential backoff:
     * - Attempt 1: Immediate
     * - Attempt 2: 100ms delay
     * - Attempt 3: 200ms delay
     * - Attempt 4: 400ms delay
     * - Attempt 5: 800ms delay
     * - After 5 failures: Calls @Recover fallback method
     *
     * @param fromAccountId Source account ID
     * @param toAccountId Destination account ID
     * @param amount Transfer amount
     * @param simulateFailure If true, simulates transient failures
     * @return Transfer result with retry metadata
     */
    @Retryable(
        retryFor = {TransientDataAccessException.class, QueryTimeoutException.class, SQLException.class},
        maxAttempts = 5,
        backoff = @Backoff(
            delay = 100,        // Initial delay: 100ms
            multiplier = 2.0,   // Exponential backoff
            maxDelay = 5000     // Cap at 5 seconds
        )
    )
    @Transactional
    @WithSpan("retry.transfer")
    public RetryResult performTransferWithRetry(
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        boolean simulateFailure
    ) throws Exception {
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();
        Instant startTime = Instant.now();

        int currentAttempt = retryAttemptsCounter.incrementAndGet();

        log.info("Retry attempt #{}: Transfer from={}, to={}, amount={}, correlationId={}",
            currentAttempt, fromAccountId, toAccountId, amount, correlationId);

        // Add retry metadata to OpenTelemetry span
        Span currentSpan = Span.current();
        currentSpan.setAttribute("retry.attempt", currentAttempt);
        currentSpan.setAttribute("retry.simulate_failure", simulateFailure);
        currentSpan.setAttribute("correlation.id", correlationId != null ? correlationId : "none");

        // Simulate transient failure for demonstration purposes
        if (simulateFailure && currentAttempt < 3) {
            log.warn("Simulating transient failure on attempt #{}", currentAttempt);

            // Record retry attempt metric
            metricsHelper.incrementCounter(
                "retry.attempts",
                "type", "transfer",
                "reason", "simulated_failure",
                "attempt", String.valueOf(currentAttempt)
            );

            throw new TransientDataAccessException("Simulated transient database failure") {};
        }

        // Perform the actual transfer
        Account fromAccount = accountRepository.findById(fromAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Source account not found: " + fromAccountId));

        Account toAccount = accountRepository.findById(toAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Destination account not found: " + toAccountId));

        // Validate sufficient funds
        if (fromAccount.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds in account " + fromAccountId);
        }

        // Execute transfer
        fromAccount.debit(amount);
        toAccount.credit(amount);

        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        // Record transaction
        Transaction transaction = new Transaction();
        transaction.setFromAccountId(fromAccountId);
        transaction.setToAccountId(toAccountId);
        transaction.setAmount(amount);
        transaction.setStatus("SUCCESS");
        transaction.setCorrelationId(correlationId);
        transactionRepository.save(transaction);

        Duration duration = Duration.between(startTime, Instant.now());

        // Record success metrics
        retrySuccessCounter.incrementAndGet();
        metricsHelper.incrementCounter(
            "retry.success",
            "type", "transfer",
            "attempts", String.valueOf(currentAttempt)
        );

        metricsHelper.recordTimer(
            "retry.duration",
            duration.toMillis(),
            "type", "transfer",
            "status", "success"
        );

        log.info("Transfer succeeded on attempt #{}: txnId={}, duration={}ms, correlationId={}",
            currentAttempt, transaction.getId(), duration.toMillis(), correlationId);

        return RetryResult.builder()
            .success(true)
            .attempts(currentAttempt)
            .transactionId(transaction.getId())
            .duration(duration)
            .fallbackUsed(false)
            .correlationId(correlationId)
            .build();
    }

    /**
     * Fallback method called when all retry attempts are exhausted.
     *
     * This method is automatically invoked by Spring Retry when the
     * @Retryable method fails after all retry attempts.
     *
     * Fallback strategy:
     * - Log the failure with correlation ID
     * - Record metrics for monitoring
     * - Return graceful failure result (don't propagate exception)
     * - Optionally queue for manual review
     *
     * @param e The exception that caused all retries to fail
     * @param fromAccountId Source account ID
     * @param toAccountId Destination account ID
     * @param amount Transfer amount
     * @param simulateFailure Simulation flag
     * @return Fallback result indicating failure
     */
    @Recover
    public RetryResult recoverFromTransferFailure(
        Exception e,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        boolean simulateFailure
    ) {
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();
        int totalAttempts = retryAttemptsCounter.get();

        fallbackCounter.incrementAndGet();
        retryFailureCounter.incrementAndGet();

        log.error("Transfer failed after {} retry attempts: from={}, to={}, amount={}, correlationId={}, error={}",
            totalAttempts, fromAccountId, toAccountId, amount, correlationId, e.getMessage(), e);

        // Record fallback metrics
        metricsHelper.incrementCounter(
            "retry.fallback",
            "type", "transfer",
            "reason", e.getClass().getSimpleName()
        );

        metricsHelper.incrementCounter(
            "retry.failure",
            "type", "transfer",
            "attempts", String.valueOf(totalAttempts)
        );

        // Add to OpenTelemetry span
        Span currentSpan = Span.current();
        currentSpan.setAttribute("retry.fallback", true);
        currentSpan.setAttribute("retry.total_attempts", totalAttempts);
        currentSpan.setAttribute("retry.error", e.getMessage());

        return RetryResult.builder()
            .success(false)
            .attempts(totalAttempts)
            .transactionId(null)
            .duration(Duration.ZERO)
            .fallbackUsed(true)
            .errorMessage(e.getMessage())
            .correlationId(correlationId)
            .build();
    }

    /**
     * Tests database connection with retry logic.
     *
     * Useful for verifying database availability and connection pool health.
     * Retries on connection failures with exponential backoff.
     *
     * @return Connection test result with retry metadata
     */
    @Retryable(
        retryFor = {SQLException.class, TransientDataAccessException.class},
        maxAttempts = 5,
        backoff = @Backoff(delay = 100, multiplier = 2.0, maxDelay = 5000)
    )
    @WithSpan("retry.connection_test")
    public RetryResult testConnectionWithRetry() throws SQLException {
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();
        Instant startTime = Instant.now();
        int currentAttempt = retryAttemptsCounter.incrementAndGet();

        log.info("Connection retry attempt #{}: correlationId={}", currentAttempt, correlationId);

        // Attempt to get connection from pool
        try (Connection connection = dataSource.getConnection()) {
            boolean isValid = connection.isValid(5); // 5 second timeout

            if (!isValid) {
                log.warn("Connection validation failed on attempt #{}", currentAttempt);
                throw new SQLException("Connection validation failed");
            }

            Duration duration = Duration.between(startTime, Instant.now());
            retrySuccessCounter.incrementAndGet();

            log.info("Connection test succeeded on attempt #{}: duration={}ms, correlationId={}",
                currentAttempt, duration.toMillis(), correlationId);

            return RetryResult.builder()
                .success(true)
                .attempts(currentAttempt)
                .duration(duration)
                .fallbackUsed(false)
                .correlationId(correlationId)
                .build();
        }
    }

    /**
     * Fallback for connection test failures.
     */
    @Recover
    public RetryResult recoverFromConnectionFailure(SQLException e) {
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();
        int totalAttempts = retryAttemptsCounter.get();

        fallbackCounter.incrementAndGet();
        retryFailureCounter.incrementAndGet();

        log.error("Connection test failed after {} attempts: correlationId={}, error={}",
            totalAttempts, correlationId, e.getMessage(), e);

        metricsHelper.incrementCounter(
            "retry.fallback",
            "type", "connection",
            "reason", "connection_failure"
        );

        return RetryResult.builder()
            .success(false)
            .attempts(totalAttempts)
            .duration(Duration.ZERO)
            .fallbackUsed(true)
            .errorMessage(e.getMessage())
            .correlationId(correlationId)
            .build();
    }

    /**
     * Gets comprehensive retry metrics for monitoring and debugging.
     *
     * Metrics include:
     * - Total retry attempts across all operations
     * - Success count (operations that eventually succeeded)
     * - Failure count (operations that failed after all retries)
     * - Fallback count (operations that used fallback logic)
     * - Success rate percentage
     *
     * @return Map of metric names to values
     */
    public Map<String, Object> getRetryMetrics() {
        int attempts = retryAttemptsCounter.get();
        int successes = retrySuccessCounter.get();
        int failures = retryFailureCounter.get();
        int fallbacks = fallbackCounter.get();

        double successRate = attempts > 0
            ? (successes * 100.0 / (successes + failures))
            : 0.0;

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalAttempts", attempts);
        metrics.put("totalSuccesses", successes);
        metrics.put("totalFailures", failures);
        metrics.put("totalFallbacks", fallbacks);
        metrics.put("successRate", String.format("%.2f%%", successRate));
        metrics.put("averageAttemptsPerOperation",
            (successes + failures) > 0 ? attempts * 1.0 / (successes + failures) : 0.0);

        log.debug("Retry metrics: {}", metrics);
        return metrics;
    }

    /**
     * Resets all retry metric counters.
     *
     * Useful for testing and demonstrations to start with clean metrics.
     */
    public void resetMetrics() {
        retryAttemptsCounter.set(0);
        retrySuccessCounter.set(0);
        retryFailureCounter.set(0);
        fallbackCounter.set(0);
        log.info("Retry metrics reset");
    }

    /**
     * Result object for retry operations.
     */
    @lombok.Builder
    @lombok.Data
    public static class RetryResult {
        private boolean success;
        private int attempts;
        private Long transactionId;
        private Duration duration;
        private boolean fallbackUsed;
        private String errorMessage;
        private String correlationId;
    }
}
