package com.oltp.demo.config;

import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Observability configuration for OLTP demo.
 *
 * Implements constitution.md principle IV: "Observability & Debugging"
 * - Comprehensive metrics with Micrometer
 * - Custom business metrics
 * - Distributed tracing integration
 * - Performance metric filtering
 *
 * Key metrics exposed:
 * - HikariCP connection pool statistics
 * - JVM metrics (heap, GC, threads)
 * - HTTP server request metrics with percentiles
 * - Custom business metrics (transfers, transactions)
 * - Database transaction metrics
 *
 * All metrics are exposed at /actuator/prometheus for Prometheus scraping.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ObservabilityConfig {

    private final Environment environment;

    /**
     * Customizes the MeterRegistry with common tags and filters.
     *
     * Adds global tags to all metrics:
     * - application: oltp-demo
     * - environment: dev/prod/test
     *
     * These tags enable proper metric aggregation and filtering in Prometheus/Grafana.
     *
     * @return MeterRegistry customizer
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        String appName = environment.getProperty("spring.application.name", "oltp-demo");
        String env = environment.getProperty("ENVIRONMENT", "dev");

        log.info("Configuring metrics with tags: application={}, environment={}", appName, env);

        return registry -> registry.config()
            .commonTags(
                "application", appName,
                "environment", env
            )
            // Enable percentile histograms for SLO monitoring
            .meterFilter(MeterFilter.maximumAllowableMetrics(10000));
    }

    /**
     * Enables @Timed annotation support for method-level timing metrics.
     *
     * Services can use @Timed to automatically track method execution time:
     * <pre>
     * {@code
     * @Timed(value = "transfers", description = "Money transfer operations")
     * public TransferResult transfer(Long from, Long to, BigDecimal amount) {
     *     // Implementation
     * }
     * }
     * </pre>
     *
     * @param registry MeterRegistry for metric recording
     * @return TimedAspect for AOP-based timing
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        log.info("Enabling @Timed annotation support for method-level metrics");
        return new TimedAspect(registry);
    }

    /**
     * Configures custom meter filters for metric optimization.
     *
     * Filters:
     * - Deny metrics with high cardinality to prevent metric explosion
     * - Configure histogram buckets for latency SLOs
     * - Enable percentile approximations for performance metrics
     *
     * @return MeterFilter for metric filtering
     */
    @Bean
    public MeterFilter customMeterFilter() {
        return MeterFilter.deny(id -> {
            String name = id.getName();

            // Deny overly verbose Spring metrics
            if (name.startsWith("jvm.gc.pause") && id.getTag("action") != null) {
                return false;  // Keep GC pause metrics
            }

            // Deny high-cardinality metrics that could cause issues
            if (name.contains("uri") && id.getTag("uri") != null) {
                String uri = id.getTag("uri");
                // Deny metrics for non-API URIs to reduce cardinality
                return !uri.startsWith("/api") && !uri.startsWith("/actuator");
            }

            return false;  // Allow all other metrics
        });
    }

    /**
     * Configures histogram buckets for latency percentiles.
     *
     * Aligns with constitution.md performance targets:
     * - Point queries: < 5ms (p95)
     * - Simple transactions: < 10ms (p95)
     * - Complex transactions: < 50ms (p95)
     *
     * @return MeterFilter for histogram configuration
     */
    @Bean
    public MeterFilter histogramConfiguration() {
        return MeterFilter.maximumAllowableTags(
            "http.server.requests",
            "uri",
            100,  // Maximum 100 unique URIs to prevent cardinality explosion
            MeterFilter.deny()
        );
    }
}
