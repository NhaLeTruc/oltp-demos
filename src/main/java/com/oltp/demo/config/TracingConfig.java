package com.oltp.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.annotations.WithSpan;

/**
 * OpenTelemetry distributed tracing configuration (T150-T153).
 *
 * Configures OpenTelemetry instrumentation for distributed tracing across:
 * - HTTP requests/responses
 * - Database queries (JDBC)
 * - Service method calls
 * - Async operations
 *
 * Traces are exported to Jaeger via OTLP (OpenTelemetry Protocol) on port 4317.
 *
 * Key Features:
 * - W3C Trace Context propagation
 * - Correlation ID integration via Baggage
 * - @WithSpan annotation support for custom spans
 * - Automatic JDBC query tracing
 * - Span attributes for debugging
 *
 * Configuration:
 * - Trace sampling rate: 100% (configurable via TRACING_SAMPLE_RATE)
 * - Exporter: OTLP gRPC to localhost:4317
 * - Service name: oltp-demo
 * - Resource attributes: environment, version, namespace
 *
 * Usage in Services:
 * <pre>{@code
 * @Service
 * public class TransferService {
 *
 *     @WithSpan("transfer.execute")
 *     public void executeTransfer(Long fromId, Long toId, BigDecimal amount) {
 *         Span.current().setAttribute("transfer.amount", amount.toString());
 *         Span.current().setAttribute("account.from", fromId);
 *         Span.current().setAttribute("account.to", toId);
 *         // Business logic
 *     }
 * }
 * }</pre>
 *
 * View Traces:
 * - Jaeger UI: http://localhost:16686
 * - Search by: trace ID, service name, operation name, tags (correlation.id)
 *
 * See: specs/001-oltp-core-demo/spec.md - US4: Comprehensive Observability
 *
 * @see com.oltp.demo.util.CorrelationIdFilter
 * @see WithSpan
 */
@Configuration
public class TracingConfig {

    /**
     * Configures OpenTelemetry tracer bean.
     *
     * The tracer is used to create custom spans programmatically:
     * <pre>{@code
     * Span span = tracer.spanBuilder("operation-name").startSpan();
     * try (Scope scope = span.makeCurrent()) {
     *     // Business logic
     *     span.setAttribute("key", "value");
     * } finally {
     *     span.end();
     * }
     * }</pre>
     *
     * However, prefer using @WithSpan annotation when possible for cleaner code.
     *
     * @param openTelemetry OpenTelemetry instance (auto-configured by Spring Boot)
     * @return Tracer instance
     */
    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(
            "com.oltp.demo",
            "1.0.0"
        );
    }

    /**
     * Note: OpenTelemetry auto-instrumentation is configured in application.yml:
     *
     * otel:
     *   instrumentation:
     *     jdbc:
     *       enabled: true
     *       statement-sanitizer:
     *         enabled: true
     *     http:
     *       client:
     *         enabled: true
     *       server:
     *         enabled: true
     *     spring:
     *       enabled: true
     *     jpa:
     *       enabled: true
     *
     * This automatically traces:
     * - All HTTP requests/responses
     * - All JDBC queries (with SQL statement sanitization)
     * - All JPA operations
     * - Spring @Async methods
     * - RestTemplate/WebClient calls
     *
     * Additional manual instrumentation can be added via @WithSpan annotation.
     */
}
