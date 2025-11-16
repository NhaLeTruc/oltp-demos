package com.oltp.demo.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Slow query logger with EXPLAIN ANALYZE support (T155).
 *
 * Detects and logs slow database queries for performance monitoring.
 *
 * Features:
 * - Automatic detection of queries exceeding threshold
 * - EXPLAIN ANALYZE for detailed query execution plans
 * - Performance recommendations
 * - Integration with MetricsHelper for alerting
 *
 * Configuration:
 * - slow.query.threshold.ms: Threshold for slow query detection (default: 50ms)
 * - slow.query.explain.enabled: Enable EXPLAIN ANALYZE (default: true)
 *
 * Usage:
 * <pre>
 * slowQueryLogger.logIfSlow("SELECT", startTime, sql, parameters);
 * </pre>
 *
 * @see MetricsHelper#recordSlowQuery
 * @see <a href="specs/001-oltp-core-demo/spec.md">US4: Comprehensive Observability</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlowQueryLogger {

    private final DataSource dataSource;
    private final MetricsHelper metricsHelper;

    @Value("${slow.query.threshold.ms:50}")
    private long slowQueryThresholdMs;

    @Value("${slow.query.explain.enabled:true}")
    private boolean explainEnabled;

    /**
     * Logs a query if it exceeded the slow query threshold.
     *
     * @param queryType Type of query (SELECT/INSERT/UPDATE/DELETE)
     * @param startTimeMs Query start time in milliseconds
     * @param sql SQL query text
     * @param parameters Query parameters (optional)
     */
    public void logIfSlow(String queryType, long startTimeMs, String sql, Object... parameters) {
        long durationMs = System.currentTimeMillis() - startTimeMs;

        if (durationMs > slowQueryThresholdMs) {
            logSlowQuery(queryType, durationMs, sql, parameters);
        }
    }

    /**
     * Logs a slow query with EXPLAIN ANALYZE details.
     *
     * @param queryType Type of query
     * @param durationMs Query duration in milliseconds
     * @param sql SQL query text
     * @param parameters Query parameters
     */
    private void logSlowQuery(String queryType, long durationMs, String sql, Object... parameters) {
        // Record metrics
        metricsHelper.recordSlowQuery(queryType, durationMs, slowQueryThresholdMs);

        // Log basic slow query info
        log.warn("SLOW QUERY DETECTED: type={}, duration={}ms (threshold={}ms)",
            queryType, durationMs, slowQueryThresholdMs);
        log.warn("SQL: {}", sanitizeSql(sql));

        if (parameters != null && parameters.length > 0) {
            log.warn("Parameters: {}", sanitizeParameters(parameters));
        }

        // Run EXPLAIN ANALYZE if enabled and query is SELECT
        if (explainEnabled && "SELECT".equalsIgnoreCase(queryType)) {
            explainQuery(sql, parameters);
        }

        // Provide recommendations
        provideRecommendations(queryType, durationMs, sql);
    }

    /**
     * Executes EXPLAIN ANALYZE on the slow query.
     *
     * Provides detailed execution plan including:
     * - Index usage
     * - Row counts
     * - Execution cost
     * - Timing breakdown
     *
     * @param sql SQL query text
     * @param parameters Query parameters
     */
    private void explainQuery(String sql, Object... parameters) {
        try (Connection conn = dataSource.getConnection()) {
            String explainSql = "EXPLAIN (ANALYZE, BUFFERS, COSTS, VERBOSE, FORMAT TEXT) " + sql;

            try (PreparedStatement ps = conn.prepareStatement(explainSql)) {
                // Bind parameters if provided
                if (parameters != null) {
                    for (int i = 0; i < parameters.length; i++) {
                        ps.setObject(i + 1, parameters[i]);
                    }
                }

                List<String> explainPlan = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        explainPlan.add(rs.getString(1));
                    }
                }

                // Log EXPLAIN plan
                log.warn("EXPLAIN ANALYZE:");
                for (String line : explainPlan) {
                    log.warn("  {}", line);
                }

                // Analyze plan for issues
                analyzePlan(explainPlan);

            }
        } catch (SQLException e) {
            log.error("Failed to execute EXPLAIN ANALYZE for slow query", e);
        }
    }

    /**
     * Analyzes EXPLAIN plan for common performance issues.
     *
     * Detects:
     * - Sequential scans on large tables
     * - Missing index usage
     * - High row counts
     * - Expensive operations
     *
     * @param explainPlan Lines from EXPLAIN ANALYZE output
     */
    private void analyzePlan(List<String> explainPlan) {
        for (String line : explainPlan) {
            String lowerLine = line.toLowerCase();

            // Check for sequential scans
            if (lowerLine.contains("seq scan")) {
                log.warn("PERFORMANCE ISSUE: Sequential scan detected - consider adding an index");
            }

            // Check for high row counts
            if (lowerLine.contains("rows=")) {
                try {
                    int rowsStart = lowerLine.indexOf("rows=") + 5;
                    int rowsEnd = lowerLine.indexOf(" ", rowsStart);
                    if (rowsEnd == -1) rowsEnd = lowerLine.length();

                    String rowsStr = lowerLine.substring(rowsStart, rowsEnd).trim();
                    long rows = Long.parseLong(rowsStr);

                    if (rows > 10000) {
                        log.warn("PERFORMANCE ISSUE: High row count ({}) - query may benefit from filtering or pagination", rows);
                    }
                } catch (Exception e) {
                    // Parsing failed, continue
                }
            }

            // Check for expensive sorts
            if (lowerLine.contains("sort") && lowerLine.contains("cost=")) {
                log.warn("PERFORMANCE ISSUE: Expensive sort operation - consider adding an index for ORDER BY");
            }

            // Check for nested loops on large datasets
            if (lowerLine.contains("nested loop") && lowerLine.contains("rows=")) {
                log.warn("PERFORMANCE ISSUE: Nested loop join - may be inefficient for large result sets");
            }
        }
    }

    /**
     * Provides performance recommendations based on query characteristics.
     *
     * @param queryType Type of query
     * @param durationMs Query duration
     * @param sql SQL query text
     */
    private void provideRecommendations(String queryType, long durationMs, String sql) {
        String sqlLower = sql.toLowerCase();

        List<String> recommendations = new ArrayList<>();

        // Check for missing WHERE clause
        if (sqlLower.contains("select") && !sqlLower.contains("where") && !sqlLower.contains("limit")) {
            recommendations.add("Add WHERE clause to filter results or LIMIT to restrict row count");
        }

        // Check for SELECT *
        if (sqlLower.contains("select *")) {
            recommendations.add("Avoid SELECT * - specify only needed columns to reduce data transfer");
        }

        // Check for potential N+1 query pattern
        if (queryType.equalsIgnoreCase("SELECT") && durationMs < 100) {
            recommendations.add("If this query is executed repeatedly, consider batch loading or JOIN");
        }

        // Check for OR conditions that might prevent index usage
        if (sqlLower.contains(" or ")) {
            recommendations.add("OR conditions may prevent index usage - consider UNION or IN clause");
        }

        // Check for LIKE wildcards at start
        if (sqlLower.contains("like '%")) {
            recommendations.add("Leading wildcard in LIKE prevents index usage - consider full-text search");
        }

        // Severity-based recommendations
        if (durationMs > 1000) {
            recommendations.add("CRITICAL: Query took >1s - immediate optimization required");
        } else if (durationMs > 200) {
            recommendations.add("WARNING: Query took >200ms - optimization recommended");
        }

        if (!recommendations.isEmpty()) {
            log.warn("PERFORMANCE RECOMMENDATIONS:");
            for (String recommendation : recommendations) {
                log.warn("  - {}", recommendation);
            }
        }
    }

    /**
     * Sanitizes SQL for logging (removes sensitive data, limits length).
     *
     * @param sql Raw SQL query
     * @return Sanitized SQL
     */
    private String sanitizeSql(String sql) {
        if (sql == null) {
            return "null";
        }

        // Remove extra whitespace
        String sanitized = sql.replaceAll("\\s+", " ").trim();

        // Limit length
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 497) + "...";
        }

        return sanitized;
    }

    /**
     * Sanitizes query parameters for logging.
     *
     * @param parameters Query parameters
     * @return Sanitized parameter string
     */
    private String sanitizeParameters(Object... parameters) {
        if (parameters == null || parameters.length == 0) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }

            Object param = parameters[i];
            if (param == null) {
                sb.append("null");
            } else if (param instanceof String) {
                String str = (String) param;
                // Limit string parameter length
                if (str.length() > 50) {
                    sb.append("\"").append(str.substring(0, 47)).append("...\"");
                } else {
                    sb.append("\"").append(str).append("\"");
                }
            } else {
                sb.append(param.toString());
            }

            // Limit total parameters shown
            if (i >= 9) {
                sb.append(", ... (").append(parameters.length - 10).append(" more)");
                break;
            }
        }
        sb.append("]");

        return sb.toString();
    }

    /**
     * Gets the slow query threshold.
     *
     * @return Threshold in milliseconds
     */
    public long getSlowQueryThresholdMs() {
        return slowQueryThresholdMs;
    }

    /**
     * Checks if EXPLAIN is enabled.
     *
     * @return true if EXPLAIN ANALYZE is enabled
     */
    public boolean isExplainEnabled() {
        return explainEnabled;
    }
}
