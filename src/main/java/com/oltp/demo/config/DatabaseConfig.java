package com.oltp.demo.config;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Database configuration for OLTP demo.
 *
 * Configures HikariCP connection pool with production-grade settings
 * optimized for OLTP workloads per constitution.md targets:
 * - Connection acquisition < 1ms (p95)
 * - Support for 1000+ TPS throughput
 *
 * Key optimizations:
 * - Prepared statement caching
 * - Batch statement rewriting
 * - Connection health monitoring
 * - Metrics exposition via Micrometer
 */
@Slf4j
@Configuration
@EnableTransactionManagement
public class DatabaseConfig {

    /**
     * Primary DataSource bean with HikariCP connection pooling.
     *
     * Configuration is loaded from application.yml under spring.datasource.hikari
     * and can be overridden via environment variables.
     *
     * HikariCP is chosen for:
     * - Fastest connection pool performance (per research.md)
     * - Built-in metrics for monitoring
     * - Excellent connection leak detection
     *
     * @param properties Spring Boot DataSource properties
     * @param meterRegistry Micrometer registry for metrics
     * @return configured HikariCP DataSource
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(
            DataSourceProperties properties,
            MeterRegistry meterRegistry) {

        log.info("Configuring HikariCP DataSource for OLTP workload");

        // Create HikariConfig from Spring DataSourceProperties
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setDriverClassName(properties.getDriverClassName());

        // Pool sizing (from application.yml, overridable)
        // Default: max=20, min=10 for high-concurrency OLTP
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(10);

        // Connection timeouts
        // Constitution target: connection acquisition < 1ms p95
        config.setConnectionTimeout(250);  // 250ms max wait
        config.setIdleTimeout(600000);     // 10 minutes
        config.setMaxLifetime(1800000);    // 30 minutes

        // Connection health and leak detection
        config.setLeakDetectionThreshold(60000);  // Warn if connection held > 60s
        config.setValidationTimeout(3000);

        // IMPORTANT: Disable auto-commit for explicit transaction control
        // This aligns with @Transactional and ACID demonstration requirements
        config.setAutoCommit(false);

        // Pool name for monitoring
        config.setPoolName("OltpHikariPool");

        // Performance optimizations for PostgreSQL
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        // Batch statement rewriting for batch operations
        config.addDataSourceProperty("rewriteBatchedStatements", "true");

        // Register metrics with Micrometer
        config.setMetricRegistry(meterRegistry);

        HikariDataSource dataSource = new HikariDataSource(config);

        log.info("HikariCP DataSource configured: pool={}, max={}, min={}",
                config.getPoolName(),
                config.getMaximumPoolSize(),
                config.getMinimumIdle());

        return dataSource;
    }

    /**
     * DataSource properties bean for externalized configuration.
     *
     * Loads configuration from application.yml under spring.datasource
     * and supports environment variable overrides:
     * - DATABASE_URL
     * - DATABASE_USER
     * - DATABASE_PASSWORD
     *
     * @return DataSource properties
     */
    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }
}
