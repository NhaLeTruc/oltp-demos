package com.oltp.demo.service.performance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating connection pooling performance benefits.
 *
 * Compares:
 * - Pooled connections (HikariCP) - reuses existing connections
 * - Unpooled connections - creates new connection for each query
 *
 * Expected results:
 * - 10x faster query execution with connection pooling
 * - Lower resource consumption with pooling
 * - Scalability benefits under concurrent load
 *
 * HikariCP is configured in DatabaseConfig with optimal settings.
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US3: Performance Under Load</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionPoolingService {

    private final DataSource dataSource;  // HikariCP pooled datasource

    private static final String SAMPLE_QUERY =
        "SELECT id, account_number, balance, status FROM accounts WHERE id = ?";

    /**
     * Demonstrates pooled connection performance with HikariCP.
     *
     * Executes multiple queries using the connection pool.
     * Connections are reused, minimizing overhead.
     *
     * @param iterations Number of queries to execute
     * @return Performance results with timing and statistics
     */
    public PoolingResult demonstratePooledConnections(int iterations) {
        long startTime = System.currentTimeMillis();
        int successfulQueries = 0;
        List<Long> queryTimes = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            long queryStart = System.nanoTime();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SAMPLE_QUERY)) {

                ps.setLong(1, (i % 10) + 1);  // Query accounts 1-10

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        successfulQueries++;
                    }
                }

                long queryTime = System.nanoTime() - queryStart;
                queryTimes.add(queryTime / 1_000);  // Convert to microseconds

            } catch (SQLException e) {
                log.error("Pooled query failed: {}", e.getMessage());
            }
        }

        long totalDurationMs = System.currentTimeMillis() - startTime;

        PoolStatistics poolStats = getPoolStatistics();

        return new PoolingResult(
            true,  // isPooled
            iterations,
            successfulQueries,
            totalDurationMs,
            calculateAverage(queryTimes),
            calculateMedian(queryTimes),
            calculateP95(queryTimes),
            calculateP99(queryTimes),
            poolStats
        );
    }

    /**
     * Demonstrates unpooled connection performance.
     *
     * Creates a new database connection for each query.
     * Shows the overhead of connection establishment.
     *
     * @param iterations Number of queries to execute
     * @return Performance results for comparison
     */
    public PoolingResult demonstrateUnpooledConnections(int iterations) {
        long startTime = System.currentTimeMillis();
        int successfulQueries = 0;
        List<Long> queryTimes = new ArrayList<>();

        // Create unpooled datasource
        DataSource unpooledDataSource = createUnpooledDataSource();

        for (int i = 0; i < iterations; i++) {
            long queryStart = System.nanoTime();

            try (Connection conn = unpooledDataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SAMPLE_QUERY)) {

                ps.setLong(1, (i % 10) + 1);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        successfulQueries++;
                    }
                }

                long queryTime = System.nanoTime() - queryStart;
                queryTimes.add(queryTime / 1_000);

            } catch (SQLException e) {
                log.error("Unpooled query failed: {}", e.getMessage());
            }
        }

        long totalDurationMs = System.currentTimeMillis() - startTime;

        return new PoolingResult(
            false,  // isPooled
            iterations,
            successfulQueries,
            totalDurationMs,
            calculateAverage(queryTimes),
            calculateMedian(queryTimes),
            calculateP95(queryTimes),
            calculateP99(queryTimes),
            null  // No pool stats for unpooled
        );
    }

    /**
     * Demonstrates concurrent query execution with connection pooling.
     *
     * Simulates realistic concurrent access patterns.
     * Shows connection pool's ability to handle multiple simultaneous queries.
     *
     * @param concurrentClients Number of concurrent clients
     * @param queriesPerClient Queries per client
     * @return Concurrent execution results
     */
    public ConcurrentPoolingResult demonstrateConcurrentPooling(
            int concurrentClients,
            int queriesPerClient) {

        long startTime = System.currentTimeMillis();
        ExecutorService executor = Executors.newFixedThreadPool(concurrentClients);

        List<CompletableFuture<ClientResult>> futures = new ArrayList<>();

        for (int i = 0; i < concurrentClients; i++) {
            final int clientId = i;
            CompletableFuture<ClientResult> future = CompletableFuture.supplyAsync(() ->
                executeClientQueries(clientId, queriesPerClient), executor
            );
            futures.add(future);
        }

        // Wait for all clients to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long totalDurationMs = System.currentTimeMillis() - startTime;

        // Aggregate results
        int totalQueries = 0;
        int successfulQueries = 0;
        List<Long> allQueryTimes = new ArrayList<>();

        for (CompletableFuture<ClientResult> future : futures) {
            try {
                ClientResult result = future.get();
                totalQueries += result.queriesExecuted;
                successfulQueries += result.successfulQueries;
                allQueryTimes.addAll(result.queryTimesUs);
            } catch (Exception e) {
                log.error("Error aggregating client results", e);
            }
        }

        executor.shutdown();

        PoolStatistics poolStats = getPoolStatistics();

        double throughput = (totalQueries * 1000.0) / totalDurationMs;

        return new ConcurrentPoolingResult(
            concurrentClients,
            queriesPerClient,
            totalQueries,
            successfulQueries,
            totalDurationMs,
            throughput,
            calculateAverage(allQueryTimes),
            calculateP95(allQueryTimes),
            calculateP99(allQueryTimes),
            poolStats
        );
    }

    /**
     * Gets current HikariCP pool statistics.
     *
     * @return Pool statistics or null if not available
     */
    public PoolStatistics getPoolStatistics() {
        if (dataSource instanceof HikariDataSource) {
            HikariDataSource hikariDS = (HikariDataSource) dataSource;
            HikariPoolMXBean poolMXBean = hikariDS.getHikariPoolMXBean();

            return new PoolStatistics(
                poolMXBean.getTotalConnections(),
                poolMXBean.getActiveConnections(),
                poolMXBean.getIdleConnections(),
                poolMXBean.getThreadsAwaitingConnection(),
                hikariDS.getMaximumPoolSize(),
                hikariDS.getMinimumIdle()
            );
        }
        return null;
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private ClientResult executeClientQueries(int clientId, int queryCount) {
        int successful = 0;
        List<Long> queryTimes = new ArrayList<>();

        for (int i = 0; i < queryCount; i++) {
            long queryStart = System.nanoTime();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SAMPLE_QUERY)) {

                ps.setLong(1, ((clientId + i) % 10) + 1);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        successful++;
                    }
                }

                long queryTime = System.nanoTime() - queryStart;
                queryTimes.add(queryTime / 1_000);

            } catch (SQLException e) {
                log.error("Client {} query {} failed: {}", clientId, i, e.getMessage());
            }
        }

        return new ClientResult(queryCount, successful, queryTimes);
    }

    private DataSource createUnpooledDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();

        // Extract connection details from HikariCP datasource
        if (dataSource instanceof HikariDataSource) {
            HikariDataSource hikariDS = (HikariDataSource) dataSource;
            ds.setUrl(hikariDS.getJdbcUrl());
            ds.setUsername(hikariDS.getUsername());
            ds.setPassword(hikariDS.getPassword());
        }

        return ds;
    }

    private double calculateAverage(List<Long> values) {
        if (values.isEmpty()) return 0;
        return values.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private long calculateMedian(List<Long> values) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2;
        }
        return sorted.get(mid);
    }

    private long calculateP95(List<Long> values) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private long calculateP99(List<Long> values) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(0.99 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    // =========================================================================
    // Result DTOs
    // =========================================================================

    public record PoolingResult(
        boolean isPooled,
        int totalQueries,
        int successfulQueries,
        long totalDurationMs,
        double avgQueryTimeUs,
        long medianQueryTimeUs,
        long p95QueryTimeUs,
        long p99QueryTimeUs,
        PoolStatistics poolStatistics
    ) {
        public double queriesPerSecond() {
            return (successfulQueries * 1000.0) / totalDurationMs;
        }

        public double speedupFactor(PoolingResult unpooled) {
            if (!isPooled || unpooled.isPooled) return 1.0;
            return unpooled.avgQueryTimeUs / this.avgQueryTimeUs;
        }
    }

    public record ConcurrentPoolingResult(
        int concurrentClients,
        int queriesPerClient,
        int totalQueries,
        int successfulQueries,
        long totalDurationMs,
        double throughputQps,
        double avgQueryTimeUs,
        long p95QueryTimeUs,
        long p99QueryTimeUs,
        PoolStatistics poolStatistics
    ) {}

    public record PoolStatistics(
        int totalConnections,
        int activeConnections,
        int idleConnections,
        int threadsAwaiting,
        int maxPoolSize,
        int minIdle
    ) {
        public double poolUtilization() {
            return (activeConnections * 100.0) / totalConnections;
        }
    }

    private record ClientResult(
        int queriesExecuted,
        int successfulQueries,
        List<Long> queryTimesUs
    ) {}
}
