# Distributed Tracing with OpenTelemetry and Jaeger

**Demonstration**: Distributed tracing, span creation, and trace visualization

**User Story**: US4 - Comprehensive Observability (T169)

**Functional Requirements**: FR-017

## Overview

This demonstration shows how to implement distributed tracing using OpenTelemetry to capture end-to-end request flows, visualize them in Jaeger, and analyze performance bottlenecks across services and database calls.

### What You'll Learn

- OpenTelemetry automatic instrumentation
- Custom span creation with `@WithSpan` annotation
- Trace context propagation (W3C Trace Context)
- Correlation ID integration with traces
- Jaeger UI navigation and trace analysis
- Database query tracing with JDBC instrumentation

### Prerequisites

- Application running on `localhost:8080`
- Jaeger running on `localhost:16686` (optional, for visualization)
- OTLP collector on `localhost:4317` (configured in application)

## Architecture

```
┌────────────────────┐
│   HTTP Request     │
│                    │
│  Traceparent:      │
│  00-trace-span-01  │
└─────────┬──────────┘
          │
          ▼
┌─────────────────────────────────────┐
│ OpenTelemetry Auto-Instrumentation │
│ - HTTP Server Span                  │
│ - Correlation ID in Baggage         │
│ - W3C Trace Context                 │
└─────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────┐
│ Custom Business Spans               │
│ @WithSpan("transfer.execute")       │
│ - Span Attributes                   │
│ - Exception Recording               │
└─────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────┐
│ Database Query Spans (JDBC)         │
│ - SQL Statement (sanitized)         │
│ - Query Duration                    │
│ - Connection Pool Wait              │
└─────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────┐
│ OTLP Exporter → Jaeger              │
│ - gRPC Protocol (port 4317)         │
│ - Batch Export                      │
│ - Compression (gzip)                │
└─────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────┐
│ Jaeger UI (http://localhost:16686)  │
│ - Search by service/operation/tags  │
│ - Trace visualization               │
│ - Performance analysis               │
└─────────────────────────────────────┘
```

## Running Jaeger

### Using Docker

```bash
# Run Jaeger all-in-one (development mode)
docker run -d \
  --name jaeger \
  -e COLLECTOR_OTLP_ENABLED=true \
  -p 16686:16686 \
  -p 4317:4317 \
  -p 4318:4318 \
  jaegertracing/all-in-one:latest

# Access Jaeger UI
open http://localhost:16686
```

### Using Docker Compose

```yaml
# docker-compose.yml
version: '3.8'

services:
  jaeger:
    image: jaegertracing/all-in-one:latest
    ports:
      - "16686:16686"  # Jaeger UI
      - "4317:4317"    # OTLP gRPC
      - "4318:4318"    # OTLP HTTP
    environment:
      - COLLECTOR_OTLP_ENABLED=true
      - LOG_LEVEL=debug
```

```bash
docker-compose up -d jaeger
```

## Trace Hierarchy

### HTTP Request Trace Example

```
Trace ID: a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4
Duration: 45ms

├─ [ROOT] HTTP GET /api/health (45ms)
│  ├─ Attributes:
│  │  ├─ http.method: GET
│  │  ├─ http.url: /api/health
│  │  ├─ http.status_code: 200
│  │  └─ correlation.id: abc-123
│  │
│  ├─ [CHILD] database.health_check (15ms)
│  │  ├─ SQL: SELECT 1
│  │  └─ Connection pool wait: 2ms
│  │
│  ├─ [CHILD] redis.health_check (8ms)
│  │  └─ Command: PING
│  │
│  └─ [CHILD] memory.health_check (1ms)
```

### Transfer Transaction Trace

```
Trace ID: 7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f
Duration: 27ms

├─ [ROOT] HTTP POST /api/demos/acid/atomicity/transfer (27ms)
│  ├─ correlation.id: xyz-789
│  ├─ http.method: POST
│  └─ http.status_code: 200
│  │
│  ├─ [CHILD] atomicity.transfer.successful (25ms)
│  │  ├─ transfer.from_account_id: 1
│  │  ├─ transfer.to_account_id: 2
│  │  ├─ transfer.amount: 100.00
│  │  └─ correlation.id: xyz-789
│  │  │
│  │  ├─ [GRANDCHILD] SELECT FROM accounts WHERE id = ? (3ms)
│  │  │  └─ SQL (sanitized): SELECT ... FROM accounts WHERE id = ?
│  │  │
│  │  ├─ [GRANDCHILD] INSERT INTO transactions (8ms)
│  │  │  └─ SQL: INSERT INTO transactions ...
│  │  │
│  │  ├─ [GRANDCHILD] UPDATE accounts SET balance = ? WHERE id = ? (4ms)
│  │  │  └─ Affected rows: 1
│  │  │
│  │  ├─ [GRANDCHILD] UPDATE accounts SET balance = ? WHERE id = ? (3ms)
│  │  │  └─ Affected rows: 1
│  │  │
│  │  └─ [GRANDCHILD] UPDATE transactions SET status = ? (2ms)
```

## Automatic Instrumentation

OpenTelemetry automatically creates spans for:

### HTTP Requests

```java
// No code changes needed - automatic instrumentation
@GetMapping("/api/health")
public ResponseEntity<HealthResponse> getHealth() {
    // OpenTelemetry automatically creates:
    // - Root span for HTTP request
    // - Span attributes: method, URI, status code
    // - Correlation ID from CorrelationIdFilter
    return ResponseEntity.ok(healthResponse);
}
```

**Generated Span**:
```
Span Name: GET /api/health
Span Kind: SERVER
Attributes:
  - http.method: GET
  - http.url: /api/health
  - http.status_code: 200
  - correlation.id: abc-123 (from CorrelationIdFilter)
```

### Database Queries

```java
// JDBC instrumentation automatically traces database calls
Account account = accountRepository.findById(accountId).orElseThrow();

// OpenTelemetry creates:
// - Child span for SQL query
// - Sanitized SQL statement
// - Query duration
// - Connection pool wait time
```

**Generated Span**:
```
Span Name: SELECT accounts
Span Kind: CLIENT
Attributes:
  - db.system: postgresql
  - db.statement: SELECT * FROM accounts WHERE id = ?
  - db.connection_string: jdbc:postgresql://localhost:5432/oltpdb
  - db.user: oltp_user
```

## Custom Instrumentation

### Using `@WithSpan` Annotation

Add custom spans to business methods:

```java
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.opentelemetry.api.trace.Span;

@Service
public class TransferService {

    @WithSpan("transfer.execute")  // Creates custom span
    public TransferResult executeTransfer(Long fromId, Long toId, BigDecimal amount) {
        // Get current span to add attributes
        Span currentSpan = Span.current();
        currentSpan.setAttribute("transfer.from_account_id", fromId);
        currentSpan.setAttribute("transfer.to_account_id", toId);
        currentSpan.setAttribute("transfer.amount", amount.toString());

        try {
            // Business logic
            performTransfer(fromId, toId, amount);

            currentSpan.setStatus(StatusCode.OK);
            return TransferResult.success();

        } catch (InsufficientFundsException e) {
            // Record exception in span
            currentSpan.recordException(e);
            currentSpan.setStatus(StatusCode.ERROR, "Insufficient funds");
            throw e;
        }
    }
}
```

### Programmatic Span Creation

For more control:

```java
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

@Service
@RequiredArgsConstructor
public class BatchService {

    private final Tracer tracer;

    public void processBatch(List<Record> records) {
        // Create custom span
        Span batchSpan = tracer.spanBuilder("batch.process")
            .setAttribute("batch.size", records.size())
            .startSpan();

        try (Scope scope = batchSpan.makeCurrent()) {
            for (Record record : records) {
                // Each iteration creates child span
                Span recordSpan = tracer.spanBuilder("batch.process.record")
                    .setAttribute("record.id", record.getId())
                    .startSpan();

                try (Scope recordScope = recordSpan.makeCurrent()) {
                    processRecord(record);
                    recordSpan.setStatus(StatusCode.OK);
                } catch (Exception e) {
                    recordSpan.recordException(e);
                    recordSpan.setStatus(StatusCode.ERROR);
                } finally {
                    recordSpan.end();
                }
            }

            batchSpan.setStatus(StatusCode.OK);
        } finally {
            batchSpan.end();
        }
    }
}
```

## Jaeger UI Guide

### Accessing Jaeger

```bash
# Open Jaeger UI
open http://localhost:16686

# Or visit in browser
http://localhost:16686
```

### Search for Traces

**By Service and Operation**:
1. Service: `oltp-demo`
2. Operation: `GET /api/health` or `atomicity.transfer.successful`
3. Lookback: `Last hour`
4. Click **Find Traces**

**By Tags**:
```
correlation.id=abc-123
transfer.amount=100.00
http.status_code=500
```

**By Duration**:
```
Min Duration: 50ms
Max Duration: 1000ms
```

### Trace Visualization

**Timeline View**:
```
GET /api/health                    |███████████████████████████| 45ms
  └─ database.health_check         |████████| 15ms
  └─ redis.health_check            |████| 8ms
  └─ memory.health_check           || 1ms
```

**Span Details**:
- **Operation Name**: `GET /api/health`
- **Duration**: 45ms
- **Start Time**: 2025-11-16 10:25:30.123
- **Tags**:
  - `http.method`: GET
  - `http.url`: /api/health
  - `http.status_code`: 200
  - `correlation.id`: abc-123
- **Logs**: Exception stack traces (if any)

### Comparing Traces

1. Select multiple traces (Ctrl+Click)
2. Click **Compare**
3. See duration differences:
   ```
   Trace 1: 45ms ████████████
   Trace 2: 67ms ████████████████████
   Trace 3: 32ms ████████
   ```

### Service Dependencies

Navigate to **Dependencies** tab:

```
┌─────────┐
│  HTTP   │
│ Client  │
└────┬────┘
     │
     ▼
┌─────────────┐
│  oltp-demo  │
│   Service   │
└──┬───────┬──┘
   │       │
   ▼       ▼
┌────┐   ┌─────┐
│ DB │   │Redis│
└────┘   └─────┘
```

Shows:
- Request rate between services
- Error rate
- Average latency

## Correlation ID Integration

Correlation IDs are propagated via OpenTelemetry Baggage:

```java
// CorrelationIdFilter adds correlation ID to Baggage
Context contextWithBaggage = Context.current().with(
    Baggage.builder()
        .put("correlation.id", correlationId)
        .build()
);

// Also added as span attribute
currentSpan.setAttribute("correlation.id", correlationId);
```

**Benefits**:
- Search traces by correlation ID in Jaeger
- Correlate logs with traces
- Navigate from application logs to distributed traces

**Example Search**:
```
Tags: correlation.id=abc-123

Results:
┌─────────────────────────────────┐
│ Trace 1: GET /api/health        │
│ Duration: 45ms                  │
│ Spans: 4                        │
│ Correlation: abc-123            │
└─────────────────────────────────┘
```

## Performance Analysis

### Identifying Slow Operations

**Sort by Duration**:
1. Search for traces
2. Sort by **Longest First**
3. Identify operations taking >50ms

**Span Duration Breakdown**:
```
Total: 127ms
├─ HTTP Request: 5ms (4%)
├─ Database Queries: 98ms (77%)  ← BOTTLENECK
│  ├─ SELECT accounts: 45ms
│  ├─ UPDATE account1: 28ms
│  └─ UPDATE account2: 25ms
├─ Redis: 12ms (9%)
└─ Business Logic: 12ms (9%)
```

### Analyzing Database Queries

**Find Slow Queries**:
1. Filter by operation: `SELECT accounts`
2. Set min duration: `>50ms`
3. Review SQL statements

**N+1 Query Detection**:
```
Transfer Request (500ms total)
├─ Get Account 1 (10ms)
├─ Get Account 2 (10ms)
├─ Get Transaction History (450ms)  ← N+1 Problem
│  ├─ SELECT transactions WHERE account_id = 1 (5ms)
│  ├─ SELECT transactions WHERE account_id = 1 (5ms)
│  └─ ... (90 more identical queries)
```

**Solution**: Use JOIN or batch fetch

### Trace Sampling

For high-traffic applications:

```yaml
# application.yml
management:
  tracing:
    sampling:
      probability: 0.1  # Sample 10% of requests
```

**Intelligent Sampling**:
```java
// Always sample errors and slow requests
if (duration > 100ms || hasError) {
    sampler.shouldSample(..., ALWAYS_SAMPLE);
}
```

## Common Trace Patterns

### Successful Request

```
├─ [200 OK] POST /api/transfer (27ms)
   └─ atomicity.transfer.successful (25ms)
      └─ Status: OK
```

### Failed Request with Exception

```
├─ [500 ERROR] POST /api/transfer (12ms)
   └─ atomicity.transfer.insufficient_funds (10ms)
      ├─ Status: ERROR
      ├─ Exception: InsufficientFundsException
      └─ Stack trace: [...]
```

### Concurrent Transactions

```
Trace 1: Transfer A→B (20ms)  |████████|
Trace 2: Transfer B→C (25ms)     |██████████|
Trace 3: Transfer C→A (30ms)        |████████████|

Shows:
- Concurrent execution
- Potential deadlock scenarios
- Lock contention (if transactions overlap)
```

## Troubleshooting

### Traces Not Appearing in Jaeger

```bash
# Check OTLP exporter is configured
grep -A10 "otel:" application.yml

# Verify Jaeger is running
curl http://localhost:16686/api/services
# Should return: ["oltp-demo"]

# Check application logs for OTLP export errors
grep -i "otlp\|telemetry" logs/application.log

# Test OTLP endpoint
curl -v http://localhost:4317
# Should connect (may reject HTTP, but shows port is open)
```

### Missing Span Attributes

```java
// Ensure span is current when setting attributes
Span currentSpan = Span.current();
if (!currentSpan.getSpanContext().isValid()) {
    log.warn("No valid span context - attributes will not be recorded");
}

currentSpan.setAttribute("key", "value");
```

### Correlation ID Not in Traces

```bash
# Check CorrelationIdFilter is setting baggage
grep "Baggage.builder" src/main/java/**/CorrelationIdFilter.java

# Verify in Jaeger:
# Search for any trace → Check Tags → Look for correlation.id
```

### High Memory Usage from Tracing

```yaml
# Reduce sampling rate
management.tracing.sampling.probability: 0.01  # 1%

# Limit span attributes
otel:
  limits:
    max-attrs-per-span: 32
    max-events-per-span: 128
```

## Best Practices

### Span Naming

```java
// GOOD: Descriptive, hierarchical names
@WithSpan("transfer.execute")
@WithSpan("database.query.select_accounts")
@WithSpan("cache.get")

// BAD: Generic names
@WithSpan("doWork")
@WithSpan("query")
@WithSpan("get")
```

### Span Attributes

```java
// GOOD: Meaningful business context
span.setAttribute("transfer.amount", amount.toString());
span.setAttribute("account.id", accountId);
span.setAttribute("user.id", userId);

// BAD: High cardinality or sensitive data
span.setAttribute("credit.card.number", ccNumber);  // PII!
span.setAttribute("timestamp", System.currentTimeMillis());  // Changes every call
```

### Exception Handling

```java
// GOOD: Record exception with context
try {
    performOperation();
} catch (Exception e) {
    span.recordException(e);
    span.setStatus(StatusCode.ERROR, e.getMessage());
    throw e;
}

// BAD: Swallow exceptions
try {
    performOperation();
} catch (Exception e) {
    log.error("Failed", e);  // Trace loses error context
}
```

### Resource Cleanup

```java
// GOOD: Always end spans
Span span = tracer.spanBuilder("operation").startSpan();
try (Scope scope = span.makeCurrent()) {
    // Work here
} finally {
    span.end();  // Critical: prevents resource leaks
}

// BAD: Forgetting to end span
Span span = tracer.spanBuilder("operation").startSpan();
// Work here
// span.end() missing → memory leak!
```

## Integration with Metrics and Logs

### Three Pillars of Observability

```
┌─────────────────┐
│   Metrics       │  What is slow?
│  (Prometheus)   │  ────────────────┐
└─────────────────┘                  │
                                     ▼
┌─────────────────┐           ┌─────────────┐
│   Traces        │  Where    │   Problem   │
│   (Jaeger)      │◄──────────│   Analysis  │
└─────────────────┘           └─────────────┘
         │                            ▲
         │  Why did it fail?          │
         └────────────────────────────┘
                      │
         ┌────────────┘
         ▼
┌─────────────────┐
│   Logs          │
│  (ELK/Loki)     │
└─────────────────┘
```

### Navigation Flow

1. **Metrics alert**: Transaction latency p95 > 50ms
2. **Traces investigate**: Find slow operations, identify database queries
3. **Logs debug**: Check correlation ID logs for detailed error messages

## Summary

This demonstration shows how to:

✅ Implement distributed tracing with OpenTelemetry
✅ Auto-instrument HTTP requests and database queries
✅ Create custom spans with `@WithSpan` annotation
✅ Add span attributes for business context
✅ Propagate correlation IDs via Baggage
✅ Visualize traces in Jaeger UI
✅ Analyze performance bottlenecks
✅ Integrate traces with metrics and logs

For more demonstrations:
- [Metrics and Monitoring](./metrics.md)
- [Structured Logging](./logging.md)

## References

- [OpenTelemetry Java Documentation](https://opentelemetry.io/docs/instrumentation/java/)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [OpenTelemetry Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/)
