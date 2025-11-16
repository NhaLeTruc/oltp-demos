package com.oltp.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Main application class for OLTP Core Capabilities Tech Demo.
 *
 * This demonstration showcases production-grade OLTP patterns including:
 * - ACID transaction guarantees (Atomicity, Consistency, Isolation, Durability)
 * - Concurrency control strategies (Optimistic/Pessimistic locking)
 * - Performance optimization (Connection pooling, batching, caching, indexing)
 * - Comprehensive observability (Metrics, logging, distributed tracing)
 * - Failure handling and recovery (Retry logic, circuit breakers)
 *
 * @author OLTP Demo Team
 * @version 1.0.0
 * @see <a href="https://github.com/example/oltp-demos">GitHub Repository</a>
 */
@SpringBootApplication
@EnableTransactionManagement
@EnableCaching
@EnableRetry
public class OltpDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(OltpDemoApplication.class, args);
    }
}
