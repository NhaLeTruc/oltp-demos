package com.oltp.demo.integration.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.oltp.demo.integration.BaseIntegrationTest;
import com.oltp.demo.service.performance.ConnectionPoolingService;

import lombok.extern.slf4j.Slf4j;

/**
 * Integration tests for connection pooling performance benchmarks.
 *
 * Tests:
 * - Pooled vs unpooled connection performance
 * - Concurrent connection handling
 * - Pool statistics accuracy
 * - Connection acquisition timing
 *
 * Uses real PostgreSQL via Testcontainers for accurate measurements.
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US3: Performance Under Load</a>
 */
@Slf4j
@Tag("integration")
@Tag("performance")
public class ConnectionPoolBenchmarkTest extends BaseIntegrationTest {

    @Autowired
    private ConnectionPoolingService connectionPoolingService;

    private static final int LOW_CONCURRENCY = 10;
    private static final int MEDIUM_CONCURRENCY = 50;
    private static final int HIGH_CONCURRENCY = 100;

    // =========================================================================
    // Pooled vs Unpooled Benchmarks
    // =========================================================================

    @Test
    @DisplayName("Pooled connections should be faster than unpooled")
    void pooledConnections_ShouldBeFaster_ThanUnpooled() {
        // Given
        int iterations = 50;

        // When
        ConnectionPoolingService.PoolingResult pooledResult =
            connectionPoolingService.demonstratePooledConnections(iterations);

        ConnectionPoolingService.PoolingResult unpooledResult =
            connectionPoolingService.demonstrateUnpooledConnections(iterations);

        double speedup = pooledResult.speedupFactor(unpooledResult);

        // Then
        assertThat(pooledResult.totalQueries()).isEqualTo(iterations);
        assertThat(pooledResult.successfulQueries()).isEqualTo(iterations);
        assertThat(unpooledResult.successfulQueries()).isEqualTo(iterations);

        // Pooled should be significantly faster
        assertThat(speedup).isGreaterThan(1.5);
        assertThat(pooledResult.avgQueryTimeUs()).isLessThan(unpooledResult.avgQueryTimeUs());

        log.info("Pooled vs Unpooled: {}x speedup", speedup);
        log.info("Pooled avg: {} µs, Unpooled avg: {} µs",
            pooledResult.avgQueryTimeUs(), unpooledResult.avgQueryTimeUs());
    }

    @Test
    @DisplayName("Pooled connections should have lower P95 latency")
    void pooledConnections_ShouldHaveLowerP95_ThanUnpooled() {
        // Given
        int iterations = 100;

        // When
        ConnectionPoolingService.PoolingResult pooledResult =
            connectionPoolingService.demonstratePooledConnections(iterations);

        ConnectionPoolingService.PoolingResult unpooledResult =
            connectionPoolingService.demonstrateUnpooledConnections(iterations);

        // Then
        assertThat(pooledResult.p95QueryTimeUs()).isLessThan(unpooledResult.p95QueryTimeUs());

        log.info("Pooled P95: {} µs, Unpooled P95: {} µs",
            pooledResult.p95QueryTimeUs(), unpooledResult.p95QueryTimeUs());
    }

    // =========================================================================
    // Concurrent Connection Benchmarks
    // =========================================================================

    @Test
    @DisplayName("Pool should handle low concurrency efficiently")
    void connectionPool_ShouldHandleLowConcurrency_Efficiently() {
        // Given
        int concurrentClients = LOW_CONCURRENCY;
        int queriesPerClient = 10;

        // When
        ConnectionPoolingService.ConcurrentPoolingResult result =
            connectionPoolingService.demonstrateConcurrentPooling(
                concurrentClients,
                queriesPerClient
            );

        // Then
        assertThat(result.totalQueries()).isEqualTo(concurrentClients * queriesPerClient);
        assertThat(result.successfulQueries()).isEqualTo(concurrentClients * queriesPerClient);
        assertThat(result.failedQueries()).isZero();

        // Should maintain good throughput
        assertThat(result.queriesPerSecond()).isGreaterThan(100);

        log.info("Low concurrency ({} clients): {} queries/sec",
            concurrentClients, result.queriesPerSecond());
    }

    @Test
    @DisplayName("Pool should handle medium concurrency with acceptable performance")
    void connectionPool_ShouldHandleMediumConcurrency_WithAcceptablePerformance() {
        // Given
        int concurrentClients = MEDIUM_CONCURRENCY;
        int queriesPerClient = 5;

        // When
        ConnectionPoolingService.ConcurrentPoolingResult result =
            connectionPoolingService.demonstrateConcurrentPooling(
                concurrentClients,
                queriesPerClient
            );

        // Then
        assertThat(result.totalQueries()).isEqualTo(concurrentClients * queriesPerClient);
        assertThat(result.successfulQueries()).isGreaterThan(0);

        // Success rate should be high
        double successRate = (result.successfulQueries() * 100.0) / result.totalQueries();
        assertThat(successRate).isGreaterThan(95.0);

        log.info("Medium concurrency ({} clients): {} queries/sec, {:.1f}% success",
            concurrentClients, result.queriesPerSecond(), successRate);
    }

    @Test
    @DisplayName("Pool should handle high concurrency under stress")
    void connectionPool_ShouldHandleHighConcurrency_UnderStress() {
        // Given
        int concurrentClients = HIGH_CONCURRENCY;
        int queriesPerClient = 3;

        // When
        ConnectionPoolingService.ConcurrentPoolingResult result =
            connectionPoolingService.demonstrateConcurrentPooling(
                concurrentClients,
                queriesPerClient
            );

        // Then
        assertThat(result.totalQueries()).isEqualTo(concurrentClients * queriesPerClient);

        // Should still maintain reasonable success rate
        double successRate = (result.successfulQueries() * 100.0) / result.totalQueries();
        assertThat(successRate).isGreaterThan(90.0);

        // Throughput should still be acceptable
        assertThat(result.queriesPerSecond()).isGreaterThan(50);

        log.info("High concurrency ({} clients): {} queries/sec, {:.1f}% success",
            concurrentClients, result.queriesPerSecond(), successRate);
    }

    // =========================================================================
    // Pool Statistics Benchmarks
    // =========================================================================

    @Test
    @DisplayName("Pool statistics should reflect actual usage")
    void poolStatistics_ShouldReflectActualUsage() {
        // Given
        int iterations = 20;

        // When - Execute some queries
        connectionPoolingService.demonstratePooledConnections(iterations);

        // Get pool statistics
        ConnectionPoolingService.PoolStatistics stats =
            connectionPoolingService.getPoolStatistics();

        // Then
        assertThat(stats).isNotNull();
        assertThat(stats.totalConnections()).isGreaterThan(0);
        assertThat(stats.activeConnections()).isGreaterThanOrEqualTo(0);
        assertThat(stats.idleConnections()).isGreaterThanOrEqualTo(0);

        log.info("Pool stats - Total: {}, Active: {}, Idle: {}",
            stats.totalConnections(), stats.activeConnections(), stats.idleConnections());
    }

    @Test
    @DisplayName("Pool should maintain connections within configured limits")
    void pool_ShouldMaintainConnections_WithinConfiguredLimits() {
        // Given
        int concurrentClients = 30;
        int queriesPerClient = 5;

        // When
        connectionPoolingService.demonstrateConcurrentPooling(
            concurrentClients,
            queriesPerClient
        );

        ConnectionPoolingService.PoolStatistics stats =
            connectionPoolingService.getPoolStatistics();

        // Then
        assertThat(stats.totalConnections()).isLessThanOrEqualTo(stats.maxPoolSize());
        assertThat(stats.totalConnections()).isGreaterThanOrEqualTo(stats.minIdle());

        log.info("Pool size - Current: {}, Min: {}, Max: {}",
            stats.totalConnections(), stats.minIdle(), stats.maxPoolSize());
    }

    // =========================================================================
    // Sustained Load Benchmarks
    // =========================================================================

    @Test
    @DisplayName("Pool should maintain performance under sustained load")
    void pool_ShouldMaintainPerformance_UnderSustainedLoad() throws Exception {
        // Given
        int durationSeconds = 5;
        int concurrentThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);

        long totalQueries = 0;
        long successfulQueries = 0;
        List<Long> latencies = new ArrayList<>();

        // When - Sustained load for N seconds
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (durationSeconds * 1000L);

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < concurrentThreads; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                while (System.currentTimeMillis() < endTime) {
                    try {
                        long queryStart = System.nanoTime();
                        connectionPoolingService.demonstratePooledConnections(1);
                        long queryDuration = (System.nanoTime() - queryStart) / 1_000_000;

                        synchronized (latencies) {
                            latencies.add(queryDuration);
                        }
                    } catch (Exception e) {
                        log.error("Query failed during sustained load test", e);
                    }
                }
            }, executor);

            futures.add(future);
        }

        // Wait for all threads to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        long actualDuration = System.currentTimeMillis() - startTime;

        // Then
        totalQueries = latencies.size();
        double qps = (totalQueries * 1000.0) / actualDuration;

        assertThat(totalQueries).isGreaterThan(100);
        assertThat(qps).isGreaterThan(20);

        // Calculate percentiles
        latencies.sort(Long::compareTo);
        long p50 = latencies.get((int) (latencies.size() * 0.50));
        long p95 = latencies.get((int) (latencies.size() * 0.95));
        long p99 = latencies.get((int) (latencies.size() * 0.99));

        log.info("Sustained load ({} seconds): {:.1f} queries/sec", durationSeconds, qps);
        log.info("Latencies - p50: {} ms, p95: {} ms, p99: {} ms", p50, p95, p99);

        // Performance should remain acceptable
        assertThat(p95).isLessThan(500);  // p95 under 500ms
    }

    // =========================================================================
    // Connection Leak Detection
    // =========================================================================

    @Test
    @DisplayName("Pool should not leak connections")
    void pool_ShouldNotLeakConnections() {
        // Given
        ConnectionPoolingService.PoolStatistics initialStats =
            connectionPoolingService.getPoolStatistics();

        int initialActive = initialStats.activeConnections();

        // When - Execute queries and ensure connections are returned
        for (int i = 0; i < 10; i++) {
            connectionPoolingService.demonstratePooledConnections(5);
        }

        // Small delay to allow connections to return
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        ConnectionPoolingService.PoolStatistics finalStats =
            connectionPoolingService.getPoolStatistics();

        int finalActive = finalStats.activeConnections();

        // Then - Active connections should return to initial level
        assertThat(finalActive).isLessThanOrEqualTo(initialActive + 1);

        log.info("Connection leak check - Initial active: {}, Final active: {}",
            initialActive, finalActive);
    }
}
