package com.oltp.demo.service.failure;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oltp.demo.domain.Account;
import com.oltp.demo.domain.Transaction;
import com.oltp.demo.repository.AccountRepository;
import com.oltp.demo.repository.TransactionRepository;
import com.oltp.demo.util.CorrelationIdFilter;
import com.oltp.demo.util.MetricsHelper;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating circuit breaker pattern using Resilience4j.
 *
 * Circuit Breaker Pattern:
 * A circuit breaker prevents cascading failures by temporarily blocking calls
 * to a failing service, giving it time to recover.
 *
 * State Transitions:
 * 1. CLOSED: Normal operation, requests flow through
 *    - If failure rate exceeds threshold → OPEN
 * 2. OPEN: Fail fast, block all requests
 *    - After wait duration → HALF_OPEN
 * 3. HALF_OPEN: Test if service recovered
 *    - If test requests succeed → CLOSED
 *    - If test requests fail → OPEN
 *
 * Configuration:
 * - Failure rate threshold: 50% (opens circuit if 50% of requests fail)
 * - Minimum calls: 5 (requires 5 calls before calculating failure rate)
 * - Wait duration in OPEN: 10 seconds
 * - Permitted calls in HALF_OPEN: 3 (test with 3 requests)
 * - Slow call threshold: 2 seconds
 * - Slow call rate threshold: 50%
 *
 * Demonstrations:
 * 1. Normal operation (CLOSED state)
 * 2. Failure detection and circuit opening (CLOSED → OPEN)
 * 3. Recovery testing (OPEN → HALF_OPEN)
 * 4. Successful recovery (HALF_OPEN → CLOSED)
 * 5. Failed recovery (HALF_OPEN → OPEN)
 *
 * Metrics Tracked:
 * - circuit.breaker.state: Current state (CLOSED, OPEN, HALF_OPEN)
 * - circuit.breaker.calls.total: Total calls attempted
 * - circuit.breaker.calls.successful: Successful calls
 * - circuit.breaker.calls.failed: Failed calls
 * - circuit.breaker.calls.rejected: Calls rejected due to OPEN state
 *
 * Constitution.md alignment:
 * - Principle III: Resilience & Recovery - Prevent cascading failures
 * - Principle IV: Observability - Track circuit state for monitoring
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US5: Failure Scenarios and Recovery</a>
 */
@Slf4j
@Service
public class CircuitBreakerService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final MetricsHelper metricsHelper;
    private final CircuitBreaker transferCircuitBreaker;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CircuitBreakerService(
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        MetricsHelper metricsHelper
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.metricsHelper = metricsHelper;

        // Create circuit breaker configuration
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)                      // Open if 50% of requests fail
            .slowCallRateThreshold(50)                     // Open if 50% of requests are slow
            .slowCallDurationThreshold(Duration.ofSeconds(2))  // Define "slow" as > 2 seconds
            .minimumNumberOfCalls(5)                       // Need 5 calls before calculating failure rate
            .waitDurationInOpenState(Duration.ofSeconds(10))  // Wait 10s before trying HALF_OPEN
            .permittedNumberOfCallsInHalfOpenState(3)      // Allow 3 test calls in HALF_OPEN
            .slidingWindowSize(10)                         // Track last 10 calls for failure rate
            .recordExceptions(
                TransientDataAccessException.class,
                QueryTimeoutException.class,
                TimeoutException.class,
                RuntimeException.class
            )
            .build();

        // Create circuit breaker registry
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(config);

        // Create circuit breaker for transfers
        this.transferCircuitBreaker = circuitBreakerRegistry.circuitBreaker("transferCircuitBreaker");

        // Register event listeners for state transitions
        registerEventListeners();
    }

    /**
     * Registers event listeners to track circuit breaker state transitions.
     */
    private void registerEventListeners() {
        transferCircuitBreaker.getEventPublisher()
            .onStateTransition(event -> {
                log.warn("Circuit breaker state transition: {} → {}",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState());

                // Record state transition metric
                metricsHelper.incrementCounter(
                    "circuit.breaker.state.transition",
                    "from", event.getStateTransition().getFromState().toString(),
                    "to", event.getStateTransition().getToState().toString()
                );

                // Add to OpenTelemetry span
                Span currentSpan = Span.current();
                currentSpan.addEvent("CircuitBreakerStateTransition",
                    io.opentelemetry.api.common.Attributes.of(
                        io.opentelemetry.api.common.AttributeKey.stringKey("from_state"),
                        event.getStateTransition().getFromState().toString(),
                        io.opentelemetry.api.common.AttributeKey.stringKey("to_state"),
                        event.getStateTransition().getToState().toString()
                    ));
            });

        transferCircuitBreaker.getEventPublisher()
            .onSuccess(event -> {
                log.debug("Circuit breaker call succeeded: duration={}ms",
                    event.getElapsedDuration().toMillis());

                metricsHelper.incrementCounter(
                    "circuit.breaker.calls",
                    "type", "transfer",
                    "result", "success"
                );
            });

        transferCircuitBreaker.getEventPublisher()
            .onError(event -> {
                log.warn("Circuit breaker call failed: error={}, duration={}ms",
                    event.getThrowable().getMessage(),
                    event.getElapsedDuration().toMillis());

                metricsHelper.incrementCounter(
                    "circuit.breaker.calls",
                    "type", "transfer",
                    "result", "failure"
                );
            });

        transferCircuitBreaker.getEventPublisher()
            .onCallNotPermitted(event -> {
                log.warn("Circuit breaker rejected call: state={}",
                    transferCircuitBreaker.getState());

                metricsHelper.incrementCounter(
                    "circuit.breaker.calls",
                    "type", "transfer",
                    "result", "rejected"
                );
            });
    }

    /**
     * Performs a transfer protected by circuit breaker.
     *
     * The circuit breaker will:
     * - Allow the call if in CLOSED state
     * - Block the call if in OPEN state (fail fast)
     * - Allow limited test calls if in HALF_OPEN state
     *
     * @param fromAccountId Source account ID
     * @param toAccountId Destination account ID
     * @param amount Transfer amount
     * @param simulateFailure If true, simulates failures to trigger circuit opening
     * @return Transfer result with circuit breaker metadata
     */
    @WithSpan("circuit.breaker.transfer")
    public CircuitBreakerResult performTransferWithCircuitBreaker(
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        boolean simulateFailure
    ) {
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();
        Instant startTime = Instant.now();
        String stateBefore = transferCircuitBreaker.getState().toString();

        log.info("Transfer attempt with circuit breaker: from={}, to={}, amount={}, state={}, correlationId={}",
            fromAccountId, toAccountId, amount, stateBefore, correlationId);

        // Add circuit breaker metadata to OpenTelemetry span
        Span currentSpan = Span.current();
        currentSpan.setAttribute("circuit.breaker.state", stateBefore);
        currentSpan.setAttribute("circuit.breaker.simulate_failure", simulateFailure);
        currentSpan.setAttribute("correlation.id", correlationId != null ? correlationId : "none");

        try {
            // Decorate the transfer operation with circuit breaker
            Long transactionId = transferCircuitBreaker.executeSupplier(() ->
                performTransfer(fromAccountId, toAccountId, amount, simulateFailure, correlationId)
            );

            Duration duration = Duration.between(startTime, Instant.now());
            String stateAfter = transferCircuitBreaker.getState().toString();

            log.info("Transfer succeeded with circuit breaker: txnId={}, state={}, duration={}ms, correlationId={}",
                transactionId, stateAfter, duration.toMillis(), correlationId);

            return CircuitBreakerResult.builder()
                .success(true)
                .transactionId(transactionId)
                .stateBefore(stateBefore)
                .stateAfter(stateAfter)
                .duration(duration)
                .rejected(false)
                .correlationId(correlationId)
                .build();

        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            // Circuit is OPEN - call rejected
            Duration duration = Duration.between(startTime, Instant.now());
            String stateAfter = transferCircuitBreaker.getState().toString();

            log.warn("Transfer rejected by circuit breaker: state={}, correlationId={}",
                stateAfter, correlationId);

            currentSpan.setAttribute("circuit.breaker.rejected", true);

            return CircuitBreakerResult.builder()
                .success(false)
                .transactionId(null)
                .stateBefore(stateBefore)
                .stateAfter(stateAfter)
                .duration(duration)
                .rejected(true)
                .errorMessage("Circuit breaker is OPEN - call rejected for fast fail")
                .correlationId(correlationId)
                .build();

        } catch (Exception e) {
            // Transfer failed
            Duration duration = Duration.between(startTime, Instant.now());
            String stateAfter = transferCircuitBreaker.getState().toString();

            log.error("Transfer failed with circuit breaker: error={}, state={}, correlationId={}",
                e.getMessage(), stateAfter, correlationId, e);

            currentSpan.setAttribute("circuit.breaker.error", e.getMessage());

            return CircuitBreakerResult.builder()
                .success(false)
                .transactionId(null)
                .stateBefore(stateBefore)
                .stateAfter(stateAfter)
                .duration(duration)
                .rejected(false)
                .errorMessage(e.getMessage())
                .correlationId(correlationId)
                .build();
        }
    }

    /**
     * Internal method to perform the actual transfer.
     *
     * This is the operation protected by the circuit breaker.
     */
    @Transactional
    private Long performTransfer(
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        boolean simulateFailure,
        String correlationId
    ) throws Exception {
        // Simulate failure for demonstration
        if (simulateFailure) {
            log.warn("Simulating failure to trigger circuit breaker");
            throw new TransientDataAccessException("Simulated database failure") {};
        }

        // Perform actual transfer
        Account fromAccount = accountRepository.findById(fromAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Source account not found: " + fromAccountId));

        Account toAccount = accountRepository.findById(toAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Destination account not found: " + toAccountId));

        if (fromAccount.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds in account " + fromAccountId);
        }

        fromAccount.debit(amount);
        toAccount.credit(amount);

        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        Transaction transaction = new Transaction();
        transaction.setFromAccountId(fromAccountId);
        transaction.setToAccountId(toAccountId);
        transaction.setAmount(amount);
        transaction.setStatus("SUCCESS");
        transaction.setCorrelationId(correlationId);
        transactionRepository.save(transaction);

        return transaction.getId();
    }

    /**
     * Gets circuit breaker metrics and current state.
     *
     * Returns comprehensive metrics including:
     * - Current state (CLOSED, OPEN, HALF_OPEN)
     * - Failure rate percentage
     * - Slow call rate percentage
     * - Number of successful/failed/rejected calls
     * - Buffer size and call counts
     *
     * @return Map of metric names to values
     */
    public Map<String, Object> getCircuitBreakerMetrics() {
        CircuitBreaker.Metrics metrics = transferCircuitBreaker.getMetrics();

        Map<String, Object> metricsMap = new HashMap<>();
        metricsMap.put("state", transferCircuitBreaker.getState().toString());
        metricsMap.put("failureRate", String.format("%.2f%%", metrics.getFailureRate()));
        metricsMap.put("slowCallRate", String.format("%.2f%%", metrics.getSlowCallRate()));
        metricsMap.put("numberOfSuccessfulCalls", metrics.getNumberOfSuccessfulCalls());
        metricsMap.put("numberOfFailedCalls", metrics.getNumberOfFailedCalls());
        metricsMap.put("numberOfSlowCalls", metrics.getNumberOfSlowCalls());
        metricsMap.put("numberOfNotPermittedCalls", metrics.getNumberOfNotPermittedCalls());
        metricsMap.put("numberOfBufferedCalls", metrics.getNumberOfBufferedCalls());

        log.debug("Circuit breaker metrics: {}", metricsMap);
        return metricsMap;
    }

    /**
     * Manually transitions circuit breaker to CLOSED state.
     *
     * Useful for testing and demonstrations to reset the circuit.
     */
    public void transitionToClosedState() {
        transferCircuitBreaker.transitionToClosedState();
        log.info("Circuit breaker manually transitioned to CLOSED state");
    }

    /**
     * Manually transitions circuit breaker to OPEN state.
     *
     * Useful for testing and demonstrations to simulate circuit opening.
     */
    public void transitionToOpenState() {
        transferCircuitBreaker.transitionToOpenState();
        log.info("Circuit breaker manually transitioned to OPEN state");
    }

    /**
     * Manually transitions circuit breaker to HALF_OPEN state.
     *
     * Useful for testing recovery scenarios.
     */
    public void transitionToHalfOpenState() {
        transferCircuitBreaker.transitionToHalfOpenState();
        log.info("Circuit breaker manually transitioned to HALF_OPEN state");
    }

    /**
     * Resets circuit breaker to initial state.
     *
     * Clears all metrics and transitions to CLOSED state.
     */
    public void reset() {
        transferCircuitBreaker.reset();
        log.info("Circuit breaker reset to initial state");
    }

    /**
     * Result object for circuit breaker operations.
     */
    @lombok.Builder
    @lombok.Data
    public static class CircuitBreakerResult {
        private boolean success;
        private Long transactionId;
        private String stateBefore;
        private String stateAfter;
        private Duration duration;
        private boolean rejected;
        private String errorMessage;
        private String correlationId;
    }
}
