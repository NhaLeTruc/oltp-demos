package com.oltp.demo.performance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.HdrHistogram.Histogram;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.oltp.demo.DemoApplication;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.extern.slf4j.Slf4j;

/**
 * JMH benchmark for connection pooling performance with HdrHistogram.
 *
 * Benchmarks:
 * - Pooled vs unpooled connection acquisition
 * - Different pool sizes under varying concurrency
 * - Connection acquisition latency distribution
 * - Query execution with pooled connections
 *
 * Uses HdrHistogram for accurate latency percentile measurements:
 * - p50 (median)
 * - p95 (95th percentile)
 * - p99 (99th percentile)
 * - p99.9 (99.9th percentile)
 * - Max latency
 *
 * Usage:
 * <pre>
 * mvn clean install
 * java -jar target/benchmarks.jar ConnectionPoolBenchmark
 * </pre>
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US3: Performance Under Load</a>
 */
@Slf4j
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = {"-Xms2G", "-Xmx2G"})
public class ConnectionPoolBenchmark {

    @State(Scope.Benchmark)
    public static class BenchmarkState {

        private ConfigurableApplicationContext context;
        private HikariDataSource pooledDataSource;
        private DataSource unpooledDataSource;
        private Histogram pooledHistogram;
        private Histogram unpooledHistogram;

        @Param({"5", "10", "20"})
        public int poolSize;

        private static final String QUERY = "SELECT id, balance FROM accounts WHERE id = ? LIMIT 1";

        @Setup(Level.Trial)
        public void setup() {
            // Start Spring Boot application for pooled datasource
            context = SpringApplication.run(DemoApplication.class,
                "--spring.profiles.active=test",
                "--spring.jpa.show-sql=false",
                "--logging.level.root=WARN",
                "--spring.datasource.hikari.maximum-pool-size=" + poolSize
            );

            pooledDataSource = context.getBean(HikariDataSource.class);

            // Create unpooled datasource for comparison
            unpooledDataSource = createUnpooledDataSource();

            // Initialize histograms for latency tracking
            // Track up to 1 minute with 3 significant digits precision
            pooledHistogram = new Histogram(TimeUnit.MINUTES.toNanos(1), 3);
            unpooledHistogram = new Histogram(TimeUnit.MINUTES.toNanos(1), 3);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            // Print histogram statistics
            System.out.println("\n=== Pooled Connection Latency Distribution ===");
            printHistogramStats(pooledHistogram, "Pooled");

            System.out.println("\n=== Unpooled Connection Latency Distribution ===");
            printHistogramStats(unpooledHistogram, "Unpooled");

            if (context != null) {
                context.close();
            }
        }

        private DataSource createUnpooledDataSource() {
            // Get JDBC URL from pooled datasource
            String jdbcUrl = pooledDataSource.getJdbcUrl();
            String username = pooledDataSource.getUsername();
            String password = pooledDataSource.getPassword();

            return DataSourceBuilder
                .create()
                .url(jdbcUrl)
                .username(username)
                .password(password)
                .build();
        }

        private void printHistogramStats(Histogram histogram, String label) {
            System.out.printf("%s Latency Statistics (microseconds):%n", label);
            System.out.printf("  Count:  %,d operations%n", histogram.getTotalCount());
            System.out.printf("  Min:    %,d µs%n", histogram.getMinValue());
            System.out.printf("  p50:    %,d µs (median)%n", histogram.getValueAtPercentile(50.0));
            System.out.printf("  p95:    %,d µs%n", histogram.getValueAtPercentile(95.0));
            System.out.printf("  p99:    %,d µs%n", histogram.getValueAtPercentile(99.0));
            System.out.printf("  p99.9:  %,d µs%n", histogram.getValueAtPercentile(99.9));
            System.out.printf("  Max:    %,d µs%n", histogram.getMaxValue());
            System.out.printf("  Mean:   %,.2f µs%n", histogram.getMean());
            System.out.printf("  StdDev: %,.2f µs%n", histogram.getStdDeviation());
        }

        public void recordPooledLatency(long latencyNanos) {
            pooledHistogram.recordValue(latencyNanos / 1000); // Convert to microseconds
        }

        public void recordUnpooledLatency(long latencyNanos) {
            unpooledHistogram.recordValue(latencyNanos / 1000);
        }
    }

    // =========================================================================
    // Connection Acquisition Benchmarks
    // =========================================================================

    /**
     * Benchmark: Pooled connection acquisition and query execution.
     *
     * Measures:
     * - Time to acquire connection from pool
     * - Query execution time
     * - Connection return to pool
     */
    @Benchmark
    @Threads(4)
    public void benchmarkPooledConnection(BenchmarkState state, Blackhole blackhole) throws SQLException {
        long startTime = System.nanoTime();

        try (Connection conn = state.pooledDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(BenchmarkState.QUERY)) {

            ps.setLong(1, (System.currentTimeMillis() % 100) + 1);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    blackhole.consume(rs.getLong("id"));
                    blackhole.consume(rs.getBigDecimal("balance"));
                }
            }
        }

        long latencyNanos = System.nanoTime() - startTime;
        state.recordPooledLatency(latencyNanos);
    }

    /**
     * Benchmark: Unpooled connection acquisition and query execution.
     *
     * Creates new database connection for each operation.
     * Expected to be much slower than pooled version.
     */
    @Benchmark
    @Threads(4)
    public void benchmarkUnpooledConnection(BenchmarkState state, Blackhole blackhole) throws SQLException {
        long startTime = System.nanoTime();

        try (Connection conn = state.unpooledDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(BenchmarkState.QUERY)) {

            ps.setLong(1, (System.currentTimeMillis() % 100) + 1);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    blackhole.consume(rs.getLong("id"));
                    blackhole.consume(rs.getBigDecimal("balance"));
                }
            }
        }

        long latencyNanos = System.nanoTime() - startTime;
        state.recordUnpooledLatency(latencyNanos);
    }

    // =========================================================================
    // High Concurrency Benchmarks
    // =========================================================================

    /**
     * Benchmark: Pooled connections under high concurrency.
     *
     * Tests pool behavior with many concurrent threads.
     */
    @Benchmark
    @Threads(16)
    public void benchmarkPooledHighConcurrency(BenchmarkState state, Blackhole blackhole) throws SQLException {
        long startTime = System.nanoTime();

        try (Connection conn = state.pooledDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(BenchmarkState.QUERY)) {

            ps.setLong(1, (System.currentTimeMillis() % 100) + 1);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    blackhole.consume(rs.getLong("id"));
                }
            }
        }

        long latencyNanos = System.nanoTime() - startTime;
        state.recordPooledLatency(latencyNanos);
    }

    /**
     * Benchmark: Unpooled connections under high concurrency.
     *
     * Expected to show severe performance degradation.
     */
    @Benchmark
    @Threads(16)
    public void benchmarkUnpooledHighConcurrency(BenchmarkState state, Blackhole blackhole) throws SQLException {
        long startTime = System.nanoTime();

        try (Connection conn = state.unpooledDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(BenchmarkState.QUERY)) {

            ps.setLong(1, (System.currentTimeMillis() % 100) + 1);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    blackhole.consume(rs.getLong("id"));
                }
            }
        }

        long latencyNanos = System.nanoTime() - startTime;
        state.recordUnpooledLatency(latencyNanos);
    }

    // =========================================================================
    // Connection-Only Benchmarks (No Query)
    // =========================================================================

    /**
     * Benchmark: Pooled connection acquisition only (no query).
     *
     * Isolates pool overhead from query execution time.
     */
    @Benchmark
    @Threads(4)
    public void benchmarkPooledConnectionAcquisition(BenchmarkState state, Blackhole blackhole) throws SQLException {
        long startTime = System.nanoTime();

        try (Connection conn = state.pooledDataSource.getConnection()) {
            blackhole.consume(conn.getMetaData().getDatabaseProductName());
        }

        long latencyNanos = System.nanoTime() - startTime;
        state.recordPooledLatency(latencyNanos);
    }

    /**
     * Benchmark: Unpooled connection acquisition only (no query).
     *
     * Shows pure connection establishment overhead.
     */
    @Benchmark
    @Threads(4)
    public void benchmarkUnpooledConnectionAcquisition(BenchmarkState state, Blackhole blackhole) throws SQLException {
        long startTime = System.nanoTime();

        try (Connection conn = state.unpooledDataSource.getConnection()) {
            blackhole.consume(conn.getMetaData().getDatabaseProductName());
        }

        long latencyNanos = System.nanoTime() - startTime;
        state.recordUnpooledLatency(latencyNanos);
    }

    // =========================================================================
    // Main Method (Optional - for IDE execution)
    // =========================================================================

    /**
     * Runs benchmarks from IDE.
     *
     * Note: Prefer running via Maven for accurate results:
     * mvn clean install && java -jar target/benchmarks.jar
     */
    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
