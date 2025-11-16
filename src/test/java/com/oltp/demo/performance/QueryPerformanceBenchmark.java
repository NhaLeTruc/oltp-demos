package com.oltp.demo.performance;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import com.oltp.demo.DemoApplication;
import com.oltp.demo.domain.Account;
import com.oltp.demo.repository.AccountRepository;
import com.oltp.demo.repository.jooq.AccountJooqRepository;
import com.oltp.demo.service.performance.CachingService;
import com.oltp.demo.service.performance.IndexingDemoService;

/**
 * JMH benchmark for query performance comparisons.
 *
 * Benchmarks:
 * - JPA vs jOOQ query performance
 * - Indexed vs sequential scan queries
 * - Cached vs uncached data access
 * - Simple vs complex query performance
 *
 * Usage:
 * <pre>
 * mvn clean install
 * java -jar target/benchmarks.jar QueryPerformanceBenchmark
 * </pre>
 *
 * Or run specific benchmark:
 * <pre>
 * java -jar target/benchmarks.jar QueryPerformanceBenchmark.benchmarkJpaSimpleQuery
 * </pre>
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US3: Performance Under Load</a>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = {"-Xms2G", "-Xmx2G"})
public class QueryPerformanceBenchmark {

    @State(Scope.Benchmark)
    public static class BenchmarkState {

        private ConfigurableApplicationContext context;
        private AccountRepository accountRepository;
        private AccountJooqRepository accountJooqRepository;
        private CachingService cachingService;
        private IndexingDemoService indexingDemoService;
        private DataSource dataSource;

        @Param({"1", "10", "100"})
        public Long accountId;

        @Setup(Level.Trial)
        public void setup() {
            // Start Spring Boot application
            context = SpringApplication.run(DemoApplication.class,
                "--spring.profiles.active=test",
                "--spring.jpa.show-sql=false",
                "--logging.level.root=WARN"
            );

            // Get beans
            accountRepository = context.getBean(AccountRepository.class);
            accountJooqRepository = context.getBean(AccountJooqRepository.class);
            cachingService = context.getBean(CachingService.class);
            indexingDemoService = context.getBean(IndexingDemoService.class);
            dataSource = context.getBean(DataSource.class);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (context != null) {
                context.close();
            }
        }
    }

    // =========================================================================
    // JPA vs jOOQ Benchmarks
    // =========================================================================

    /**
     * Benchmark: Simple JPA query (findById).
     *
     * Baseline for JPA entity queries.
     */
    @Benchmark
    public void benchmarkJpaSimpleQuery(BenchmarkState state, Blackhole blackhole) {
        Optional<Account> account = state.accountRepository.findById(state.accountId);
        blackhole.consume(account);
    }

    /**
     * Benchmark: jOOQ simple query.
     *
     * Compares jOOQ query performance to JPA for simple lookups.
     */
    @Benchmark
    public void benchmarkJooqSimpleQuery(BenchmarkState state, Blackhole blackhole) {
        var accounts = state.accountJooqRepository.findAccountsWithUsers();
        blackhole.consume(accounts);
    }

    /**
     * Benchmark: JPA complex query with joins.
     *
     * JPA query fetching account with user relationship.
     */
    @Benchmark
    public void benchmarkJpaComplexQuery(BenchmarkState state, Blackhole blackhole) {
        var accounts = state.accountRepository.findAll();
        blackhole.consume(accounts);
    }

    /**
     * Benchmark: jOOQ complex query with aggregations.
     *
     * Shows jOOQ performance for complex multi-table queries.
     */
    @Benchmark
    public void benchmarkJooqComplexQuery(BenchmarkState state, Blackhole blackhole) {
        var results = state.accountJooqRepository.findAccountsWithTransactionStats(0);
        blackhole.consume(results);
    }

    // =========================================================================
    // Caching Benchmarks
    // =========================================================================

    /**
     * Benchmark: Cached query (cache hit).
     *
     * Measures Redis cache hit performance.
     */
    @Benchmark
    public void benchmarkCachedQuery(BenchmarkState state, Blackhole blackhole) {
        // First call to warm cache
        state.cachingService.getAccountCached(state.accountId);

        // Second call should hit cache
        Optional<Account> account = state.cachingService.getAccountCached(state.accountId);
        blackhole.consume(account);
    }

    /**
     * Benchmark: Uncached query (always database).
     *
     * Baseline for database query without caching.
     */
    @Benchmark
    public void benchmarkUncachedQuery(BenchmarkState state, Blackhole blackhole) {
        Optional<Account> account = state.cachingService.getAccountUncached(state.accountId);
        blackhole.consume(account);
    }

    // =========================================================================
    // Indexing Benchmarks
    // =========================================================================

    /**
     * Benchmark: Indexed query.
     *
     * Query using database index for fast lookup.
     */
    @Benchmark
    public void benchmarkIndexedQuery(BenchmarkState state, Blackhole blackhole) {
        var result = state.indexingDemoService.demonstrateIndexedQuery(state.accountId);
        blackhole.consume(result);
    }

    /**
     * Benchmark: Sequential scan query.
     *
     * Full table scan without index usage.
     */
    @Benchmark
    public void benchmarkSequentialScan(BenchmarkState state, Blackhole blackhole) {
        var result = state.indexingDemoService.demonstrateSequentialScan(BigDecimal.ZERO);
        blackhole.consume(result);
    }

    // =========================================================================
    // Window Functions Benchmark
    // =========================================================================

    /**
     * Benchmark: Window functions with jOOQ.
     *
     * Complex analytical query with ROW_NUMBER window function.
     */
    @Benchmark
    public void benchmarkWindowFunctions(BenchmarkState state, Blackhole blackhole) {
        var results = state.accountJooqRepository.findTopAccountsByTypeWithRanking();
        blackhole.consume(results);
    }

    // =========================================================================
    // Dynamic Query Building Benchmark
    // =========================================================================

    /**
     * Benchmark: Dynamic query construction.
     *
     * Measures overhead of dynamic WHERE clause building.
     */
    @Benchmark
    public void benchmarkDynamicQuery(BenchmarkState state, Blackhole blackhole) {
        var filter = new AccountJooqRepository.AccountSearchFilter(
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(10000),
            "ACTIVE",
            null,
            50
        );
        var results = state.accountJooqRepository.searchAccountsDynamic(filter);
        blackhole.consume(results);
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
