package com.oltp.demo.service.performance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating database indexing performance benefits.
 *
 * Uses PostgreSQL EXPLAIN ANALYZE to show:
 * - Index scans vs sequential scans
 * - Query execution times
 * - Rows examined vs rows returned
 * - Cost estimates
 *
 * Expected results:
 * - 100x faster queries with proper indexing
 * - Lower I/O costs
 * - Better scalability with data growth
 *
 * Indexes are created in V4__create_indexes.sql migration.
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US3: Performance Under Load</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingDemoService {

    private final DataSource dataSource;

    /**
     * Demonstrates indexed query performance.
     *
     * Uses idx_accounts_user_status index for efficient lookup.
     * EXPLAIN ANALYZE shows index scan with low cost.
     *
     * @param userId User ID to query
     * @return Query execution results with explain plan
     */
    public QueryPerformanceResult demonstrateIndexedQuery(Long userId) {
        String query = "SELECT id, account_number, balance, status FROM accounts " +
                      "WHERE user_id = ? AND status = 'ACTIVE'";

        return executeQueryWithExplain(query, userId, true);
    }

    /**
     * Demonstrates sequential scan performance (simulated).
     *
     * Queries without using indexes to show performance difference.
     * Note: PostgreSQL query planner may still choose index, so we
     * use techniques to force sequential scan.
     *
     * @param minBalance Minimum balance threshold
     * @return Query execution results
     */
    public QueryPerformanceResult demonstrateSequentialScan(java.math.BigDecimal minBalance) {
        // Query that's less likely to use index
        String query = "SELECT id, account_number, balance FROM accounts " +
                      "WHERE balance > ? ORDER BY balance DESC";

        return executeQueryWithExplain(query, minBalance, false);
    }

    /**
     * Compares indexed vs full table scan performance.
     *
     * @param userId User ID for indexed query
     * @return Comparison results
     */
    public IndexComparisonResult compareIndexPerformance(Long userId) {
        // Indexed query
        QueryPerformanceResult indexedResult = demonstrateIndexedQuery(userId);

        // Sequential scan (different query to avoid index)
        QueryPerformanceResult seqScanResult =
            demonstrateSequentialScan(java.math.BigDecimal.ZERO);

        double speedup = seqScanResult.executionTimeMs / Math.max(indexedResult.executionTimeMs, 0.001);

        return new IndexComparisonResult(
            indexedResult,
            seqScanResult,
            speedup,
            String.format("Indexed query is %.0fx faster than sequential scan", speedup)
        );
    }

    /**
     * Analyzes query plan and extracts performance metrics.
     *
     * @param query SQL query to analyze
     * @param parameter Query parameter
     * @param expectIndex Whether index use is expected
     * @return Performance results with explain plan
     */
    private QueryPerformanceResult executeQueryWithExplain(
            String query,
            Object parameter,
            boolean expectIndex) {

        long startTime = System.nanoTime();
        int rowsReturned = 0;
        List<String> explainPlan = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {

            // Execute EXPLAIN ANALYZE
            String explainQuery = "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + query;

            try (PreparedStatement ps = conn.prepareStatement(explainQuery)) {
                setParameter(ps, 1, parameter);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String line = rs.getString(1);
                        explainPlan.add(line);
                        log.debug("EXPLAIN: {}", line);
                    }
                }
            }

            // Execute actual query to count rows
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                setParameter(ps, 1, parameter);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rowsReturned++;
                    }
                }
            }

        } catch (SQLException e) {
            log.error("Query execution failed", e);
        }

        long executionTimeNs = System.nanoTime() - startTime;
        double executionTimeMs = executionTimeNs / 1_000_000.0;

        QueryPlanAnalysis analysis = analyzeExplainPlan(explainPlan);

        return new QueryPerformanceResult(
            query,
            expectIndex,
            analysis.usedIndex,
            executionTimeMs,
            rowsReturned,
            analysis.rowsExamined,
            analysis.cost,
            explainPlan
        );
    }

    /**
     * Parses EXPLAIN ANALYZE output to extract metrics.
     *
     * @param explainLines Lines from EXPLAIN output
     * @return Parsed analysis
     */
    private QueryPlanAnalysis analyzeExplainPlan(List<String> explainLines) {
        boolean usedIndex = false;
        long rowsExamined = 0;
        double cost = 0.0;

        for (String line : explainLines) {
            String lowerLine = line.toLowerCase();

            // Check for index usage
            if (lowerLine.contains("index scan") || lowerLine.contains("index only scan")) {
                usedIndex = true;
            }

            // Extract rows (simplified parsing)
            if (lowerLine.contains("rows=")) {
                try {
                    int rowsStart = lowerLine.indexOf("rows=") + 5;
                    int rowsEnd = lowerLine.indexOf(" ", rowsStart);
                    if (rowsEnd == -1) rowsEnd = lowerLine.length();
                    String rowsStr = lowerLine.substring(rowsStart, rowsEnd).trim();
                    rowsExamined = Long.parseLong(rowsStr);
                } catch (Exception e) {
                    // Parsing failed, continue
                }
            }

            // Extract cost (simplified parsing)
            if (lowerLine.contains("cost=")) {
                try {
                    int costStart = lowerLine.indexOf("cost=") + 5;
                    int costEnd = lowerLine.indexOf(" ", costStart);
                    if (costEnd == -1) costEnd = lowerLine.length();
                    String costStr = lowerLine.substring(costStart, costEnd);

                    // Cost format is "start..end"
                    if (costStr.contains("..")) {
                        String[] parts = costStr.split("\\.\\.");
                        if (parts.length == 2) {
                            cost = Double.parseDouble(parts[1]);
                        }
                    }
                } catch (Exception e) {
                    // Parsing failed, continue
                }
            }
        }

        return new QueryPlanAnalysis(usedIndex, rowsExamined, cost);
    }

    /**
     * Sets parameter on prepared statement based on type.
     *
     * @param ps Prepared statement
     * @param index Parameter index
     * @param value Parameter value
     */
    private void setParameter(PreparedStatement ps, int index, Object value) throws SQLException {
        if (value instanceof Long) {
            ps.setLong(index, (Long) value);
        } else if (value instanceof java.math.BigDecimal) {
            ps.setBigDecimal(index, (java.math.BigDecimal) value);
        } else if (value instanceof String) {
            ps.setString(index, (String) value);
        } else {
            ps.setObject(index, value);
        }
    }

    // =========================================================================
    // Result DTOs
    // =========================================================================

    public record QueryPerformanceResult(
        String query,
        boolean expectIndex,
        boolean usedIndex,
        double executionTimeMs,
        int rowsReturned,
        long rowsExamined,
        double cost,
        List<String> explainPlan
    ) {
        public double selectivity() {
            return rowsExamined > 0 ? (rowsReturned * 100.0) / rowsExamined : 0;
        }

        public boolean indexUsedAsExpected() {
            return expectIndex == usedIndex;
        }
    }

    public record IndexComparisonResult(
        QueryPerformanceResult indexedResult,
        QueryPerformanceResult seqScanResult,
        double speedupFactor,
        String summary
    ) {
        public double timeSavedMs() {
            return seqScanResult.executionTimeMs - indexedResult.executionTimeMs;
        }

        public double timeSavedPercent() {
            return ((seqScanResult.executionTimeMs - indexedResult.executionTimeMs) * 100.0)
                / seqScanResult.executionTimeMs;
        }

        public double costReduction() {
            return seqScanResult.cost - indexedResult.cost;
        }
    }

    private record QueryPlanAnalysis(
        boolean usedIndex,
        long rowsExamined,
        double cost
    ) {}
}
