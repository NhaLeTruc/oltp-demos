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

    // =========================================================================
    // US4: Comprehensive Observability - Custom Transaction Metrics (T145-T149)
    // =========================================================================

    /**
     * Records transaction throughput (T146).
     *
     * Tracks number of transactions processed per unit time by type and status.
     *
     * Metrics created:
     * - oltp.transaction.throughput (counter): Total transactions
     *
     * Tags:
     * - transaction_type: TRANSFER/DEPOSIT/WITHDRAWAL
     * - status: success/failure/rolled_back
     * - service: Service class name
     *
     * @param transactionType Type of transaction
     * @param status Status of transaction (success/failure/rolled_back)
     * @param serviceName Name of service processing transaction
     */
    public void recordTransactionThroughput(String transactionType, String status, String serviceName) {
        Counter.builder("oltp.transaction.throughput")
            .tag("transaction_type", transactionType)
            .tag("status", status)
            .tag("service", serviceName)
            .tag("correlationId", getCorrelationIdOrDefault())
            .description("Total number of transactions processed")
            .register(meterRegistry)
            .increment();

        log.debug("Transaction throughput recorded: type={}, status={}, service={}",
            transactionType, status, serviceName);
    }

    /**
     * Records transaction latency distribution (T147).
     *
     * Captures end-to-end transaction execution time with percentiles.
     *
     * Metrics created:
     * - oltp.transaction.latency (timer): Transaction latency distribution
     *
     * Tags:
     * - transaction_type: TRANSFER/DEPOSIT/WITHDRAWAL
     * - service: Service class name
     *
     * SLOs:
     * - Simple transactions: p95 < 10ms
     * - Complex transactions: p95 < 50ms
     *
     * @param transactionType Type of transaction
     * @param serviceName Name of service processing transaction
     * @param durationMs Transaction duration in milliseconds
     */
    public void recordTransactionLatency(String transactionType, String serviceName, long durationMs) {
        Timer.builder("oltp.transaction.latency")
            .tag("transaction_type", transactionType)
            .tag("service", serviceName)
            .tag("correlationId", getCorrelationIdOrDefault())
            .description("Transaction execution latency distribution")
            .publishPercentiles(0.5, 0.95, 0.99) // p50, p95, p99
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);

        // SLO violation warnings
        if (durationMs > 50) {
            log.warn("Transaction latency exceeded SLO: {}ms (target: <50ms p95) - type={}, service={}",
                durationMs, transactionType, serviceName);
        }

        log.debug("Transaction latency recorded: type={}, service={}, duration={}ms",
            transactionType, serviceName, durationMs);
    }

    /**
     * Records connection pool metrics (T148).
     *
     * Tracks HikariCP connection pool health and utilization.
     *
     * Metrics created:
     * - oltp.connection.pool.active (gauge): Active connections
     * - oltp.connection.pool.idle (gauge): Idle connections
     * - oltp.connection.pool.total (gauge): Total connections
     * - oltp.connection.pool.pending (gauge): Pending thread requests
     * - oltp.connection.pool.wait_time (timer): Wait time for connection
     *
     * @param activeConnections Number of active connections
     * @param idleConnections Number of idle connections
     * @param totalConnections Total connections in pool
     * @param pendingThreads Number of threads waiting for connection
     */
    public void recordConnectionPoolMetrics(
            int activeConnections,
            int idleConnections,
            int totalConnections,
            int pendingThreads) {

        // Register gauges for pool state
        Gauge.builder("oltp.connection.pool.active", () -> activeConnections)
            .description("Number of active connections in the pool")
            .register(meterRegistry);

        Gauge.builder("oltp.connection.pool.idle", () -> idleConnections)
            .description("Number of idle connections in the pool")
            .register(meterRegistry);

        Gauge.builder("oltp.connection.pool.total", () -> totalConnections)
            .description("Total number of connections in the pool")
            .register(meterRegistry);

        Gauge.builder("oltp.connection.pool.pending", () -> pendingThreads)
            .description("Number of threads waiting for a connection")
            .register(meterRegistry);

        // Alert if pool is exhausted
        if (pendingThreads > 0) {
            log.warn("Connection pool has {} pending threads waiting for connections", pendingThreads);
        }

        // Alert if pool utilization is high
        double utilization = totalConnections > 0 ? (activeConnections * 100.0) / totalConnections : 0;
        if (utilization > 80) {
            log.warn("Connection pool utilization high: {:.1f}% ({}/{} connections active)",
                utilization, activeConnections, totalConnections);
        }

        log.debug("Connection pool metrics recorded: active={}, idle={}, total={}, pending={}",
            activeConnections, idleConnections, totalConnections, pendingThreads);
    }

    /**
     * Records connection wait time (T148).
     *
     * Tracks how long threads wait to acquire a connection from the pool.
     *
     * @param waitTimeMs Wait time in milliseconds
     */
    public void recordConnectionWaitTime(long waitTimeMs) {
        Timer.builder("oltp.connection.pool.wait_time")
            .description("Time spent waiting for a connection from the pool")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry)
            .record(waitTimeMs, TimeUnit.MILLISECONDS);

        if (waitTimeMs > 100) {
            log.warn("Connection wait time exceeded 100ms: {}ms", waitTimeMs);
        }
    }

    /**
     * Records error rate by type and service (T149).
     *
     * Tracks application errors for monitoring and alerting.
     *
     * Metrics created:
     * - oltp.error.count (counter): Total errors
     * - oltp.error.rate (gauge): Errors per second
     *
     * Tags:
     * - error_type: SQL_EXCEPTION/OPTIMISTIC_LOCK/DEADLOCK/VALIDATION/etc
     * - service: Service class where error occurred
     * - severity: ERROR/WARN
     *
     * @param errorType Type of error
     * @param serviceName Service where error occurred
     * @param severity Error severity (ERROR/WARN)
     */
    public void recordError(String errorType, String serviceName, String severity) {
        Counter.builder("oltp.error.count")
            .tag("error_type", errorType)
            .tag("service", serviceName)
            .tag("severity", severity)
            .tag("correlationId", getCorrelationIdOrDefault())
            .description("Total number of errors by type and service")
            .register(meterRegistry)
            .increment();

        log.error("Error recorded in metrics: type={}, service={}, severity={}, correlationId={}",
            errorType, serviceName, severity, getCorrelationIdOrDefault());
    }

    /**
     * Records a database exception (T149).
     *
     * Specialized error tracking for database-related failures.
     *
     * @param exceptionClass Exception class name
     * @param sqlState SQL state code (if available)
     * @param serviceName Service where exception occurred
     */
    public void recordDatabaseException(String exceptionClass, String sqlState, String serviceName) {
        Counter.builder("oltp.database.exception")
            .tag("exception_class", exceptionClass)
            .tag("sql_state", sqlState != null ? sqlState : "unknown")
            .tag("service", serviceName)
            .tag("correlationId", getCorrelationIdOrDefault())
            .description("Database exceptions by type and SQL state")
            .register(meterRegistry)
            .increment();

        log.error("Database exception recorded: class={}, sqlState={}, service={}, correlationId={}",
            exceptionClass, sqlState, serviceName, getCorrelationIdOrDefault());
    }

    /**
     * Records slow query detection (for T156).
     *
     * Tracks queries that exceed the slow query threshold.
     *
     * @param queryType Type of query (SELECT/INSERT/UPDATE/DELETE)
     * @param durationMs Query duration in milliseconds
     * @param threshold Slow query threshold in milliseconds
     */
    public void recordSlowQuery(String queryType, long durationMs, long threshold) {
        if (durationMs > threshold) {
            Counter.builder("oltp.slow_query.count")
                .tag("query_type", queryType)
                .tag("threshold_ms", String.valueOf(threshold))
                .description("Number of slow queries detected")
                .register(meterRegistry)
                .increment();

            Timer.builder("oltp.slow_query.duration")
                .tag("query_type", queryType)
                .description("Slow query duration distribution")
                .publishPercentiles(0.95, 0.99)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

            log.warn("Slow query detected: type={}, duration={}ms (threshold={}ms)",
                queryType, durationMs, threshold);
        }
    }

    /**
     * Records cache hit/miss metrics (for caching demonstration).
     *
     * @param cacheName Name of the cache
     * @param hit Whether it was a cache hit (true) or miss (false)
     */
    public void recordCacheAccess(String cacheName, boolean hit) {
        String status = hit ? "hit" : "miss";

        Counter.builder("oltp.cache.access")
            .tag("cache", cacheName)
            .tag("status", status)
            .description("Cache access statistics")
            .register(meterRegistry)
            .increment();
    }

    /**
     * Records batch operation metrics.
     *
     * @param batchSize Number of records in batch
     * @param durationMs Batch processing duration
     * @param successCount Number of successful records
     */
    public void recordBatchOperation(int batchSize, long durationMs, int successCount) {
        Timer.builder("oltp.batch.duration")
            .tag("batch_size", String.valueOf(batchSize))
            .description("Batch operation duration")
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);

        Counter.builder("oltp.batch.records")
            .tag("status", successCount == batchSize ? "success" : "partial")
            .description("Records processed in batch operations")
            .register(meterRegistry)
            .increment(successCount);

        double throughput = batchSize > 0 ? (successCount * 1000.0) / durationMs : 0;
        log.debug("Batch operation recorded: size={}, duration={}ms, throughput={:.1f} records/sec",
            batchSize, durationMs, throughput);
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
