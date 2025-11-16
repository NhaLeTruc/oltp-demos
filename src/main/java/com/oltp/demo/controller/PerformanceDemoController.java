package com.oltp.demo.controller;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oltp.demo.repository.jooq.AccountJooqRepository;
import com.oltp.demo.service.performance.BatchOperationService;
import com.oltp.demo.service.performance.CachingService;
import com.oltp.demo.service.performance.ConnectionPoolingService;
import com.oltp.demo.service.performance.IndexingDemoService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for Performance Optimization demonstrations.
 *
 * Provides endpoints to demonstrate:
 * - Connection pooling (10x improvement)
 * - Batch operations (20x improvement)
 * - Caching with Redis (5x improvement)
 * - Database indexing (100x improvement)
 *
 * All endpoints return JSON responses with detailed performance metrics including:
 * - Execution times
 * - Throughput (queries/sec, records/sec)
 * - Speedup factors
 * - Resource utilization
 *
 * @see <a href="specs/001-oltp-core-demo/contracts/openapi.yaml">API Specification</a>
 * @see <a href="specs/001-oltp-core-demo/spec.md">US3: Performance Under Load</a>
 */
@Slf4j
@RestController
@RequestMapping("/api/demos/performance")
@RequiredArgsConstructor
@Tag(name = "Performance Demonstrations", description = "Demonstrations of performance optimization techniques")
public class PerformanceDemoController {

    private final ConnectionPoolingService connectionPoolingService;
    private final BatchOperationService batchOperationService;
    private final CachingService cachingService;
    private final IndexingDemoService indexingDemoService;
    private final AccountJooqRepository accountJooqRepository;

    // =========================================================================
    // Connection Pooling Demonstrations
    // =========================================================================

    @GetMapping("/connection-pooling")
    @Operation(summary = "Demonstrate connection pooling",
               description = "Compares pooled vs unpooled connection performance")
    public ResponseEntity<ConnectionPoolingComparison> demonstrateConnectionPooling(
            @RequestParam(defaultValue = "100") int iterations) {

        log.info("API: Connection pooling demo - iterations={}", iterations);

        try {
            // Test with pooling
            ConnectionPoolingService.PoolingResult pooledResult =
                connectionPoolingService.demonstratePooledConnections(iterations);

            // Small delay
            Thread.sleep(100);

            // Test without pooling
            ConnectionPoolingService.PoolingResult unpooledResult =
                connectionPoolingService.demonstrateUnpooledConnections(iterations);

            double speedup = pooledResult.speedupFactor(unpooledResult);

            return ResponseEntity.ok(new ConnectionPoolingComparison(
                pooledResult,
                unpooledResult,
                speedup,
                String.format("Connection pooling is %.1fx faster", speedup)
            ));

        } catch (Exception e) {
            log.error("Connection pooling demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/connection-pooling/concurrent")
    @Operation(summary = "Demonstrate concurrent connection pooling",
               description = "Shows pool performance under concurrent load")
    public ResponseEntity<ConnectionPoolingService.ConcurrentPoolingResult> demonstrateConcurrentPooling(
            @RequestParam(defaultValue = "20") int concurrentClients,
            @RequestParam(defaultValue = "10") int queriesPerClient) {

        log.info("API: Concurrent connection pooling demo - clients={}, queries={}",
                concurrentClients, queriesPerClient);

        try {
            ConnectionPoolingService.ConcurrentPoolingResult result =
                connectionPoolingService.demonstrateConcurrentPooling(
                    concurrentClients,
                    queriesPerClient
                );

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Concurrent pooling demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/connection-pooling/stats")
    @Operation(summary = "Get connection pool statistics",
               description = "Returns current HikariCP pool metrics")
    public ResponseEntity<ConnectionPoolingService.PoolStatistics> getPoolStatistics() {
        log.info("API: Get pool statistics");

        ConnectionPoolingService.PoolStatistics stats =
            connectionPoolingService.getPoolStatistics();

        if (stats != null) {
            return ResponseEntity.ok(stats);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // =========================================================================
    // Batch Operations Demonstrations
    // =========================================================================

    @PostMapping("/batch-operations")
    @Operation(summary = "Demonstrate batch operations",
               description = "Compares individual vs batch insert performance")
    public ResponseEntity<BatchOperationService.BatchComparisonResult> demonstrateBatchOperations(
            @RequestBody BatchOperationsRequest request) {

        log.info("API: Batch operations demo - records={}, batchSize={}",
                request.recordCount, request.batchSize);

        try {
            BatchOperationService.BatchComparisonResult result =
                batchOperationService.compareInsertStrategies(
                    request.recordCount,
                    request.batchSize
                );

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Batch operations demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/batch-operations/jpa")
    @Operation(summary = "Demonstrate JPA batch inserts",
               description = "Shows JPA batch processing performance")
    public ResponseEntity<BatchOperationService.BatchResult> demonstrateJpaBatch(
            @RequestParam(defaultValue = "100") int recordCount) {

        log.info("API: JPA batch demo - records={}", recordCount);

        try {
            BatchOperationService.BatchResult result =
                batchOperationService.demonstrateJpaBatchInserts(recordCount);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("JPA batch demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // =========================================================================
    // Caching Demonstrations
    // =========================================================================

    @GetMapping("/caching")
    @Operation(summary = "Demonstrate caching with Redis",
               description = "Compares cached vs uncached query performance")
    public ResponseEntity<CachingService.CacheComparisonResult> demonstrateCaching(
            @RequestParam Long accountId,
            @RequestParam(defaultValue = "10") int iterations) {

        log.info("API: Caching demo - account={}, iterations={}", accountId, iterations);

        try {
            CachingService.CacheComparisonResult result =
                cachingService.compareCachingStrategies(accountId, iterations);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Caching demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/caching/clear")
    @Operation(summary = "Clear cache",
               description = "Evicts all cached accounts")
    public ResponseEntity<Void> clearCache() {
        log.info("API: Clear cache");

        try {
            cachingService.clearAllCache();
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Clear cache failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/caching/stats")
    @Operation(summary = "Get cache statistics",
               description = "Returns current cache metrics")
    public ResponseEntity<CachingService.CacheStatistics> getCacheStatistics() {
        log.info("API: Get cache statistics");

        CachingService.CacheStatistics stats = cachingService.getCacheStatistics();

        if (stats != null) {
            return ResponseEntity.ok(stats);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // =========================================================================
    // Indexing Demonstrations
    // =========================================================================

    @GetMapping("/indexing")
    @Operation(summary = "Demonstrate database indexing",
               description = "Compares indexed vs sequential scan performance")
    public ResponseEntity<IndexingDemoService.IndexComparisonResult> demonstrateIndexing(
            @RequestParam Long userId) {

        log.info("API: Indexing demo - userId={}", userId);

        try {
            IndexingDemoService.IndexComparisonResult result =
                indexingDemoService.compareIndexPerformance(userId);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Indexing demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/indexing/indexed-query")
    @Operation(summary = "Execute indexed query with EXPLAIN",
               description = "Shows query plan for indexed lookup")
    public ResponseEntity<IndexingDemoService.QueryPerformanceResult> demonstrateIndexedQuery(
            @RequestParam Long userId) {

        log.info("API: Indexed query demo - userId={}", userId);

        try {
            IndexingDemoService.QueryPerformanceResult result =
                indexingDemoService.demonstrateIndexedQuery(userId);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Indexed query demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/indexing/sequential-scan")
    @Operation(summary = "Execute sequential scan with EXPLAIN",
               description = "Shows query plan for full table scan")
    public ResponseEntity<IndexingDemoService.QueryPerformanceResult> demonstrateSequentialScan(
            @RequestParam(defaultValue = "0") BigDecimal minBalance) {

        log.info("API: Sequential scan demo - minBalance={}", minBalance);

        try {
            IndexingDemoService.QueryPerformanceResult result =
                indexingDemoService.demonstrateSequentialScan(minBalance);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Sequential scan demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // =========================================================================
    // jOOQ Demonstrations
    // =========================================================================

    @GetMapping("/jooq/accounts-with-users")
    @Operation(summary = "Demonstrate jOOQ type-safe joins",
               description = "Shows type-safe multi-table joins with jOOQ")
    public ResponseEntity<List<AccountJooqRepository.AccountWithUserDTO>> demonstrateJooqJoins() {
        log.info("API: jOOQ joins demo");

        try {
            List<AccountJooqRepository.AccountWithUserDTO> results =
                accountJooqRepository.findAccountsWithUsers();

            return ResponseEntity.ok(results);

        } catch (Exception e) {
            log.error("jOOQ joins demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/jooq/transaction-stats")
    @Operation(summary = "Demonstrate jOOQ complex queries",
               description = "Shows aggregations and multi-table joins with jOOQ")
    public ResponseEntity<List<AccountJooqRepository.AccountTransactionSummaryDTO>> demonstrateJooqComplexQuery(
            @RequestParam(defaultValue = "0") int minTransactionCount) {

        log.info("API: jOOQ complex query demo - minTransactionCount={}", minTransactionCount);

        try {
            List<AccountJooqRepository.AccountTransactionSummaryDTO> results =
                accountJooqRepository.findAccountsWithTransactionStats(minTransactionCount);

            return ResponseEntity.ok(results);

        } catch (Exception e) {
            log.error("jOOQ complex query demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/jooq/top-accounts-by-type")
    @Operation(summary = "Demonstrate jOOQ window functions",
               description = "Shows window functions (ROW_NUMBER) with partitioning")
    public ResponseEntity<List<AccountJooqRepository.AccountRankingDTO>> demonstrateJooqWindowFunctions() {
        log.info("API: jOOQ window functions demo");

        try {
            List<AccountJooqRepository.AccountRankingDTO> results =
                accountJooqRepository.findTopAccountsByTypeWithRanking();

            return ResponseEntity.ok(results);

        } catch (Exception e) {
            log.error("jOOQ window functions demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/jooq/high-value-accounts")
    @Operation(summary = "Demonstrate jOOQ CTE queries",
               description = "Shows Common Table Expressions (WITH clause)")
    public ResponseEntity<List<AccountJooqRepository.HighValueAccountDTO>> demonstrateJooqCTE(
            @RequestParam(defaultValue = "1000") BigDecimal minBalance) {

        log.info("API: jOOQ CTE demo - minBalance={}", minBalance);

        try {
            List<AccountJooqRepository.HighValueAccountDTO> results =
                accountJooqRepository.findHighValueAccountsWithCTE(minBalance);

            return ResponseEntity.ok(results);

        } catch (Exception e) {
            log.error("jOOQ CTE demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/jooq/join-performance")
    @Operation(summary = "Demonstrate JOIN vs N+1 query performance",
               description = "Compares efficient JOIN with inefficient N+1 queries")
    public ResponseEntity<AccountJooqRepository.JoinPerformanceComparisonDTO> demonstrateJoinPerformance() {
        log.info("API: JOIN performance comparison demo");

        try {
            AccountJooqRepository.JoinPerformanceComparisonDTO result =
                accountJooqRepository.compareJoinPerformance();

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("JOIN performance demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // =========================================================================
    // Request DTOs
    // =========================================================================

    public record BatchOperationsRequest(
        int recordCount,
        int batchSize
    ) {}

    public record ConnectionPoolingComparison(
        ConnectionPoolingService.PoolingResult pooledResult,
        ConnectionPoolingService.PoolingResult unpooledResult,
        double speedupFactor,
        String summary
    ) {}
}
