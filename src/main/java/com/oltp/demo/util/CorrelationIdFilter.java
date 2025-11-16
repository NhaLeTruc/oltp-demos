package com.oltp.demo.util;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import lombok.extern.slf4j.Slf4j;

/**
 * Servlet filter for correlation ID management.
 *
 * Implements constitution.md principle IV: "Observability & Debugging"
 * by enabling distributed tracing through correlation IDs.
 *
 * Functionality:
 * 1. Extracts correlation ID from request header (X-Correlation-ID)
 * 2. Generates new UUID if not present
 * 3. Adds correlation ID to MDC (Mapped Diagnostic Context) for logging
 * 4. Includes correlation ID in response header
 * 5. Cleans up MDC after request processing
 *
 * The correlation ID is propagated across:
 * - HTTP request/response headers
 * - Application logs (via MDC)
 * - Database transactions table
 * - Metrics tags (via MetricsHelper)
 * - Distributed traces (OpenTelemetry)
 *
 * This enables end-to-end request tracking for debugging and troubleshooting.
 *
 * @see com.oltp.demo.util.MetricsHelper
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)  // Execute first in filter chain
public class CorrelationIdFilter implements Filter {

    /**
     * Header name for correlation ID.
     * Configurable via application.yml: oltp.demo.observability.correlation-id-header
     */
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    /**
     * MDC key for correlation ID.
     * This key is used in logback-spring.xml for structured logging.
     */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Get current span (created by OpenTelemetry auto-instrumentation)
        Span currentSpan = Span.current();

        try {
            // Extract or generate correlation ID
            String correlationId = extractOrGenerateCorrelationId(httpRequest);

            // Add to MDC for logging
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);

            // Add to response header for client tracking
            httpResponse.setHeader(CORRELATION_ID_HEADER, correlationId);

            // T151: Add correlation ID to OpenTelemetry span attributes
            // This makes correlation ID searchable in Jaeger and other tracing UIs
            currentSpan.setAttribute("correlation.id", correlationId);
            currentSpan.setAttribute("http.request.method", httpRequest.getMethod());
            currentSpan.setAttribute("http.request.uri", httpRequest.getRequestURI());

            // Add correlation ID to OpenTelemetry Baggage for cross-service propagation
            // Baggage is automatically propagated to downstream services
            Context contextWithBaggage = Context.current().with(
                Baggage.builder()
                    .put("correlation.id", correlationId)
                    .build()
            );

            log.debug("Processing request with correlationId: {} traceId: {} spanId: {}",
                correlationId, currentSpan.getSpanContext().getTraceId(),
                currentSpan.getSpanContext().getSpanId());

            // Continue filter chain with updated context
            try (Scope scope = contextWithBaggage.makeCurrent()) {
                chain.doFilter(request, response);
            }

            // Mark span as successful
            currentSpan.setStatus(StatusCode.OK);

        } catch (Exception e) {
            // Record exception in span
            currentSpan.recordException(e);
            currentSpan.setStatus(StatusCode.ERROR, "Request processing failed: " + e.getMessage());
            throw e;
        } finally {
            // CRITICAL: Always clean up MDC to prevent memory leaks
            // MDC is thread-local, so we must clear it after request
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

    /**
     * Extracts correlation ID from request header or generates a new one.
     *
     * Priority:
     * 1. Use X-Correlation-ID header if present and valid UUID
     * 2. Generate new UUID if header missing or invalid
     *
     * @param request the HTTP request
     * @return correlation ID (never null)
     */
    private String extractOrGenerateCorrelationId(HttpServletRequest request) {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);

        if (correlationId != null && !correlationId.trim().isEmpty()) {
            // Validate that it's a valid UUID format
            try {
                UUID.fromString(correlationId);
                log.trace("Using correlation ID from request header: {}", correlationId);
                return correlationId;
            } catch (IllegalArgumentException e) {
                log.warn("Invalid correlation ID in header: {}. Generating new one.", correlationId);
            }
        }

        // Generate new correlation ID
        String newCorrelationId = UUID.randomUUID().toString();
        log.trace("Generated new correlation ID: {}", newCorrelationId);
        return newCorrelationId;
    }

    /**
     * Gets the current correlation ID from MDC.
     *
     * Useful for service classes that need to access the correlation ID.
     *
     * @return correlation ID from MDC, or null if not set
     */
    public static String getCurrentCorrelationId() {
        return MDC.get(CORRELATION_ID_MDC_KEY);
    }

    /**
     * Sets a correlation ID in MDC.
     *
     * Useful for async operations or background jobs that don't go through
     * the servlet filter.
     *
     * @param correlationId the correlation ID to set
     */
    public static void setCorrelationId(String correlationId) {
        if (correlationId != null) {
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        }
    }

    /**
     * Clears the correlation ID from MDC.
     *
     * Should be called in finally block after async operations.
     */
    public static void clearCorrelationId() {
        MDC.remove(CORRELATION_ID_MDC_KEY);
    }
}
