package com.oltp.demo.util;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Helper class for custom business metrics.
 *
 * Implements constitution.md principle IV: "Observability & Debugging"
 * by providing convenient methods for recording custom metrics.
 *
 * Metrics exposed via Prometheus at /actuator/prometheus:
 * - Counters: Monotonically increasing values (e.g., transfer count)
 * - Timers: Latency distribution with percentiles (e.g., transfer duration)
 * - Gauges: Point-in-time values (e.g., active accounts count)
 *
 * All metrics are tagged with:
 * - application: oltp-demo
 * - environment: dev/prod
 * - correlationId: (when available from MDC)
 *
 * Performance targets (from constitution.md):
 * - Point queries: < 5ms (p95)
 * - Simple transactions: < 10ms (p95)
 * - Complex transactions: < 50ms (p95)
 *
 * @see com.oltp.demo.config.ObservabilityConfig
 * @see com.oltp.demo.util.CorrelationIdFilter
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsHelper {

    private final MeterRegistry meterRegistry;

    // Metric name prefixes
    private static final String TRANSFER_PREFIX = "transfer";
    private static final String ACCOUNT_PREFIX = "account";
    private static final String TRANSACTION_PREFIX = "transaction";

    /**
     * Records a transfer operation with timing and outcome.
     *
     * Metrics created:
     * - transfer.count (counter): Total transfers attempted
     * - transfer.duration (timer): Transfer latency distribution
     *
     * Tags:
     * - status: success/failure
     * - type: TRANSFER/DEPOSIT/WITHDRAWAL
     *
     * @param type the transfer type
     * @param success whether the transfer succeeded
     * @param durationMs the transfer duration in milliseconds
     */
    public void recordTransfer(String type, boolean success, long durationMs) {
        String status = success ? "success" : "failure";

        // Increment counter
        Counter.builder(TRANSFER_PREFIX + ".count")
            .tag("type", type)
            .tag("status", status)
            .tag("correlationId", getCorrelationIdOrDefault())
            .description("Total number of transfer operations")
            .register(meterRegistry)
            .increment();

        // Record duration
        Timer.builder(TRANSFER_PREFIX + ".duration")
            .tag("type", type)
            .tag("status", status)
            .description("Transfer operation duration")
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);

        // Warn if transfer exceeded SLO
        if (durationMs > 10) {  // Simple transactions should be < 10ms (p95)
            log.warn("Transfer duration exceeded SLO: {}ms (target: <10ms p95)", durationMs);
        }
    }

    /**
     * Records a transaction with timing.
     *
     * @param type the transaction type
     * @param durationMs the transaction duration in milliseconds
     */
    public void recordTransaction(String type, long durationMs) {
        Timer.builder(TRANSACTION_PREFIX + ".duration")
            .tag("type", type)
            .description("Transaction duration")
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Increments a custom counter.
     *
     * @param name the counter name
     * @param tags optional tags (must be even number: key1, value1, key2, value2, ...)
     */
    public void incrementCounter(String name, String... tags) {
        Counter.Builder builder = Counter.builder(name);

        // Add tags in pairs
        for (int i = 0; i < tags.length - 1; i += 2) {
            builder.tag(tags[i], tags[i + 1]);
        }

        builder.tag("correlationId", getCorrelationIdOrDefault())
            .register(meterRegistry)
            .increment();
    }

    /**
     * Records a timer value.
     *
     * @param name the timer name
     * @param durationMs the duration in milliseconds
     * @param tags optional tags (must be even number)
     */
    public void recordTimer(String name, long durationMs, String... tags) {
        Timer.Builder builder = Timer.builder(name);

        // Add tags in pairs
        for (int i = 0; i < tags.length - 1; i += 2) {
            builder.tag(tags[i], tags[i + 1]);
        }

        builder.register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Executes a supplier and times it, returning the result.
     *
     * Example usage:
     * <pre>
     * TransferResult result = metricsHelper.timeOperation("transfer", () -> {
     *     return performTransfer(from, to, amount);
     * });
     * </pre>
     *
     * @param <T> the return type
     * @param name the operation name for metrics
     * @param supplier the operation to execute
     * @return the result of the operation
     */
    public <T> T timeOperation(String name, Supplier<T> supplier) {
        long startTime = System.currentTimeMillis();
        try {
            return supplier.get();
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            recordTimer(name, duration);
        }
    }

    /**
     * Registers a gauge for a value supplier.
     *
     * Gauges are useful for values that go up and down (e.g., active connections).
     *
     * Example:
     * <pre>
     * metricsHelper.registerGauge("active.accounts", () -> accountRepository.countByStatus(ACTIVE));
     * </pre>
     *
     * @param name the gauge name
     * @param valueSupplier the supplier for the gauge value
     * @param tags optional tags
     */
    public void registerGauge(String name, Supplier<Number> valueSupplier, String... tags) {
        Gauge.Builder<?> builder = Gauge.builder(name, valueSupplier);

        // Add tags in pairs
        for (int i = 0; i < tags.length - 1; i += 2) {
            builder.tag(tags[i], tags[i + 1]);
        }

        builder.register(meterRegistry);
    }

    /**
     * Records an optimistic lock exception.
     *
     * Used to track contention and retry rates in concurrency demonstrations.
     */
    public void recordOptimisticLockException() {
        incrementCounter("concurrency.optimistic_lock_exception", "type", "optimistic");
    }

    /**
     * Records a pessimistic lock acquisition.
     *
     * Used to track lock usage in concurrency demonstrations.
     */
    public void recordPessimisticLock() {
        incrementCounter("concurrency.pessimistic_lock", "type", "pessimistic");
    }

    /**
     * Records a deadlock detection.
     *
     * Used to track deadlock frequency in concurrency demonstrations.
     */
    public void recordDeadlock() {
        incrementCounter("concurrency.deadlock", "type", "deadlock");
        log.warn("Deadlock detected and recorded in metrics");
    }

    /**
     * Gets correlation ID from MDC or returns "none".
     *
     * @return correlation ID or "none"
     */
    private String getCorrelationIdOrDefault() {
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();
        return correlationId != null ? correlationId : "none";
    }
}
