package com.oltp.demo.controller;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for metrics and health endpoints (T157-T159).
 *
 * Provides observability endpoints for monitoring application health and metrics:
 * - Prometheus-format metrics
 * - Custom health checks
 * - Database health
 * - Redis cache health
 * - Connection pool statistics
 *
 * Endpoints:
 * - GET /api/metrics: Prometheus-format metrics
 * - GET /api/health: Comprehensive health check
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US4: Comprehensive Observability</a>
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Metrics & Health", description = "Observability and monitoring endpoints")
public class MetricsController {

    private final MeterRegistry meterRegistry;
    private final DataSource dataSource;
    private final CacheManager cacheManager;
    private final RedisConnectionFactory redisConnectionFactory;

    // =========================================================================
    // Metrics Endpoint (T158)
    // =========================================================================

    /**
     * Exposes metrics in Prometheus format.
     *
     * Returns all application metrics including:
     * - JVM metrics (heap, GC, threads)
     * - Database connection pool metrics
     * - Custom business metrics (transactions, errors, etc.)
     * - Cache metrics
     * - HTTP request metrics
     *
     * Usage:
     * <pre>
     * curl http://localhost:8080/api/metrics
     * </pre>
     *
     * Configure Prometheus to scrape this endpoint:
     * <pre>
     * scrape_configs:
     *   - job_name: 'oltp-demo'
     *     static_configs:
     *       - targets: ['localhost:8080']
     *     metrics_path: '/api/metrics'
     * </pre>
     *
     * @return Prometheus-format metrics
     */
    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "Get Prometheus metrics",
               description = "Returns all application metrics in Prometheus text format")
    public ResponseEntity<String> getPrometheusMetrics() {
        log.debug("Metrics endpoint accessed");

        try {
            if (meterRegistry instanceof PrometheusMeterRegistry) {
                PrometheusMeterRegistry prometheusRegistry = (PrometheusMeterRegistry) meterRegistry;
                String metrics = prometheusRegistry.scrape();

                return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(metrics);
            } else {
                log.warn("MeterRegistry is not a PrometheusMeterRegistry, returning empty metrics");
                return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("# Prometheus metrics not available\n");
            }
        } catch (Exception e) {
            log.error("Failed to generate Prometheus metrics", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("# Error generating metrics: " + e.getMessage() + "\n");
        }
    }

    // =========================================================================
    // Health Endpoint (T159)
    // =========================================================================

    /**
     * Comprehensive health check endpoint.
     *
     * Checks health of:
     * - Application status
     * - Database connectivity and pool health
     * - Redis cache connectivity
     * - Memory usage
     *
     * Returns HTTP 200 if healthy, 503 if unhealthy.
     *
     * Response format:
     * <pre>
     * {
     *   "status": "UP",
     *   "components": {
     *     "database": {
     *       "status": "UP",
     *       "details": {
     *         "connected": true,
     *         "pool": {
     *           "active": 2,
     *           "idle": 8,
     *           "total": 10
     *         }
     *       }
     *     },
     *     "redis": {
     *       "status": "UP",
     *       "details": {
     *         "connected": true,
     *         "ping": "PONG"
     *       }
     *     }
     *   }
     * }
     * </pre>
     *
     * @return Health status
     */
    @GetMapping("/health")
    @Operation(summary = "Get application health",
               description = "Comprehensive health check including database and Redis")
    public ResponseEntity<HealthResponse> getHealth() {
        log.debug("Health check endpoint accessed");

        HealthResponse response = new HealthResponse();
        response.status = "UP";
        response.components = new HashMap<>();

        // Check database health
        HealthComponent dbHealth = checkDatabaseHealth();
        response.components.put("database", dbHealth);
        if (!"UP".equals(dbHealth.status)) {
            response.status = "DOWN";
        }

        // Check Redis health
        HealthComponent redisHealth = checkRedisHealth();
        response.components.put("redis", redisHealth);
        if (!"UP".equals(redisHealth.status)) {
            response.status = "DOWN";
        }

        // Check memory health
        HealthComponent memoryHealth = checkMemoryHealth();
        response.components.put("memory", memoryHealth);

        // Return appropriate HTTP status
        if ("UP".equals(response.status)) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
    }

    /**
     * Checks database connectivity and pool health.
     *
     * @return Database health status
     */
    private HealthComponent checkDatabaseHealth() {
        HealthComponent health = new HealthComponent();
        health.status = "UP";
        health.details = new HashMap<>();

        try {
            // Test database connectivity
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {

                health.details.put("connected", rs.next());
            }

            // Get connection pool stats
            if (dataSource instanceof HikariDataSource) {
                HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
                HikariPoolMXBean poolMXBean = hikariDataSource.getHikariPoolMXBean();

                Map<String, Object> poolStats = new HashMap<>();
                poolStats.put("active", poolMXBean.getActiveConnections());
                poolStats.put("idle", poolMXBean.getIdleConnections());
                poolStats.put("total", poolMXBean.getTotalConnections());
                poolStats.put("pending", poolMXBean.getThreadsAwaitingConnection());

                health.details.put("pool", poolStats);

                // Check for pool exhaustion
                if (poolMXBean.getThreadsAwaitingConnection() > 0) {
                    health.status = "DEGRADED";
                    health.details.put("warning", "Connection pool has pending threads");
                }
            }

        } catch (SQLException e) {
            log.error("Database health check failed", e);
            health.status = "DOWN";
            health.details.put("connected", false);
            health.details.put("error", e.getMessage());
        }

        return health;
    }

    /**
     * Checks Redis connectivity.
     *
     * @return Redis health status
     */
    private HealthComponent checkRedisHealth() {
        HealthComponent health = new HealthComponent();
        health.status = "UP";
        health.details = new HashMap<>();

        try {
            RedisConnection connection = redisConnectionFactory.getConnection();

            // Test Redis with PING command
            String pong = connection.ping();
            health.details.put("connected", true);
            health.details.put("ping", pong != null ? pong : "PONG");

            connection.close();

        } catch (Exception e) {
            log.error("Redis health check failed", e);
            health.status = "DOWN";
            health.details.put("connected", false);
            health.details.put("error", e.getMessage());
        }

        return health;
    }

    /**
     * Checks memory usage.
     *
     * @return Memory health status
     */
    private HealthComponent checkMemoryHealth() {
        HealthComponent health = new HealthComponent();
        health.status = "UP";
        health.details = new HashMap<>();

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double usagePercent = (usedMemory * 100.0) / maxMemory;

        health.details.put("used", formatBytes(usedMemory));
        health.details.put("free", formatBytes(freeMemory));
        health.details.put("total", formatBytes(totalMemory));
        health.details.put("max", formatBytes(maxMemory));
        health.details.put("usage_percent", String.format("%.1f%%", usagePercent));

        // Warning if memory usage is high
        if (usagePercent > 90) {
            health.status = "DEGRADED";
            health.details.put("warning", "Memory usage above 90%");
        }

        return health;
    }

    /**
     * Formats bytes as human-readable string.
     *
     * @param bytes Number of bytes
     * @return Formatted string (e.g., "1.5 GB")
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    // =========================================================================
    // Response DTOs
    // =========================================================================

    public static class HealthResponse {
        public String status;
        public Map<String, HealthComponent> components;
    }

    public static class HealthComponent {
        public String status;
        public Map<String, Object> details;
    }
}
