# Structured Logging with Correlation IDs

**Demonstration**: Structured logging, correlation ID propagation, and log analysis

**User Story**: US4 - Comprehensive Observability (T168)

**Functional Requirements**: FR-015

## Overview

This demonstration shows how to implement structured logging with correlation IDs for end-to-end request tracing. Correlation IDs enable tracking a single request across multiple services, log files, and distributed systems.

### What You'll Learn

- How to implement correlation ID propagation
- Structured logging vs. traditional logging
- Log aggregation and analysis techniques
- Correlation ID usage for debugging distributed transactions
- Integration with ELK stack (Elasticsearch, Logstash, Kibana)

### Prerequisites

- Application running on `localhost:8080`
- Basic understanding of HTTP headers
- (Optional) ELK stack for log aggregation

## Architecture

```
┌──────────────┐
│HTTP Client   │  X-Correlation-ID: abc-123
└──────┬───────┘
       │
       ▼
┌──────────────────────────────────────┐
│ CorrelationIdFilter                  │
│ - Extract/Generate Correlation ID   │
│ - Add to MDC (Logging Context)      │
│ - Add to Response Header             │
│ - Add to OpenTelemetry Span          │
└──────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────┐
│ Application Logs                     │
│ [abc-123] INFO: Transfer started     │
│ [abc-123] DEBUG: Debit account 1     │
│ [abc-123] DEBUG: Credit account 2    │
│ [abc-123] INFO: Transfer complete    │
└──────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────┐
│ Log Aggregation (ELK/Loki)           │
│ - Search by correlation ID           │
│ - Reconstruct request timeline       │
│ - Analyze distributed traces         │
└──────────────────────────────────────┘
```

## Correlation ID Flow

### 1. Client Provides Correlation ID

```bash
# Client sends correlation ID in header
curl -H "X-Correlation-ID: 550e8400-e29b-41d4-a716-446655440000" \
     http://localhost:8080/api/health

# Response includes the same correlation ID
# X-Correlation-ID: 550e8400-e29b-41d4-a716-446655440000
```

**Application Logs**:
```
2025-11-16 10:23:45.123 [550e8400-e29b-41d4-a716-446655440000] INFO  c.o.d.controller.MetricsController - Health check endpoint accessed
2025-11-16 10:23:45.125 [550e8400-e29b-41d4-a716-446655440000] DEBUG c.o.d.controller.MetricsController - Checking database health
2025-11-16 10:23:45.142 [550e8400-e29b-41d4-a716-446655440000] INFO  c.o.d.controller.MetricsController - Health check complete: status=UP
```

### 2. Server Generates Correlation ID

```bash
# Client doesn't provide correlation ID
curl http://localhost:8080/api/health

# Server generates UUID and returns it
# X-Correlation-ID: 7c9e6679-7425-40de-944b-e07fc1f90ae7
```

**Application Logs**:
```
2025-11-16 10:24:12.456 [7c9e6679-7425-40de-944b-e07fc1f90ae7] INFO  c.o.d.util.CorrelationIdFilter - Generated new correlation ID
2025-11-16 10:24:12.457 [7c9e6679-7425-40de-944b-e07fc1f90ae7] INFO  c.o.d.controller.MetricsController - Health check endpoint accessed
```

## Structured Logging Format

### Standard Format

```
<timestamp> [<correlation-id>] <level> <logger> - <message>
```

### Example Logs

```log
2025-11-16 10:25:30.123 [abc-123] INFO  c.o.d.s.a.AtomicityDemoService - Starting atomic transfer: from=1, to=2, amount=100.00, correlationId=abc-123
2025-11-16 10:25:30.125 [abc-123] DEBUG c.o.d.repository.AccountRepository - Finding account by ID: 1
2025-11-16 10:25:30.142 [abc-123] DEBUG c.o.d.repository.AccountRepository - Finding account by ID: 2
2025-11-16 10:25:30.145 [abc-123] DEBUG c.o.d.domain.Account - Debiting 100.00 from account 1, new balance: 900.00
2025-11-16 10:25:30.147 [abc-123] DEBUG c.o.d.domain.Account - Crediting 100.00 to account 2, new balance: 1100.00
2025-11-16 10:25:30.150 [abc-123] INFO  c.o.d.s.a.AtomicityDemoService - Transfer completed successfully: txnId=1, duration=27ms
```

### JSON Format (for ELK)

Enable JSON logging for machine-readable logs:

```json
{
  "timestamp": "2025-11-16T10:25:30.123Z",
  "correlationId": "abc-123",
  "level": "INFO",
  "logger": "com.oltp.demo.service.acid.AtomicityDemoService",
  "message": "Starting atomic transfer",
  "context": {
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": "100.00"
  },
  "thread": "http-nio-8080-exec-1",
  "traceId": "a1b2c3d4e5f6",
  "spanId": "12345678"
}
```

## Log Levels

### Production Configuration

```yaml
# application.yml
logging:
  level:
    root: INFO
    com.oltp.demo: INFO          # Application logs
    org.springframework: WARN     # Spring framework
    org.hibernate: WARN           # Hibernate
    com.zaxxer.hikari: INFO       # Connection pool
```

### Development Configuration

```yaml
logging:
  level:
    root: INFO
    com.oltp.demo: DEBUG          # Detailed application logs
    org.springframework.web: INFO # HTTP request logs
    org.hibernate.SQL: DEBUG      # SQL queries
    org.hibernate.type: TRACE     # SQL parameters
```

### Log Level Guidelines

- **ERROR**: Unexpected errors requiring immediate attention
- **WARN**: Potential issues (slow queries, deprecated APIs, SLO violations)
- **INFO**: Important business events (transfers, user actions, system state changes)
- **DEBUG**: Detailed debugging information (method entry/exit, variable values)
- **TRACE**: Very detailed debugging (SQL parameters, internal framework details)

## Log Analysis Patterns

### 1. Trace Single Request

```bash
# Find all logs for a specific correlation ID
grep "abc-123" application.log

# Or with color highlighting
grep --color=always "abc-123" application.log | less -R

# Count log entries for correlation ID
grep "abc-123" application.log | wc -l
```

**Example Output**:
```
2025-11-16 10:25:30.123 [abc-123] INFO  - Starting atomic transfer
2025-11-16 10:25:30.125 [abc-123] DEBUG - Finding account by ID: 1
2025-11-16 10:25:30.142 [abc-123] DEBUG - Finding account by ID: 2
2025-11-16 10:25:30.145 [abc-123] DEBUG - Debiting 100.00 from account 1
2025-11-16 10:25:30.147 [abc-123] DEBUG - Crediting 100.00 to account 2
2025-11-16 10:25:30.150 [abc-123] INFO  - Transfer completed successfully
```

### 2. Timeline Reconstruction

```bash
# Sort by timestamp to see request timeline
grep "abc-123" application.log | sort -k1,2

# Calculate request duration
start=$(grep "abc-123.*Starting atomic transfer" application.log | awk '{print $2}')
end=$(grep "abc-123.*Transfer completed" application.log | awk '{print $2}')
echo "Duration: $start to $end"
```

### 3. Error Investigation

```bash
# Find all errors for correlation ID
grep "abc-123" application.log | grep -E "(ERROR|Exception|Failed)"

# Context around error (5 lines before and after)
grep -B5 -A5 "abc-123.*ERROR" application.log

# All errors in last hour
grep "ERROR" application.log | tail -n 100
```

### 4. Performance Analysis

```bash
# Find slow transactions (>50ms)
grep "duration=" application.log | awk -F'duration=' '{print $2}' | \
  awk '{if ($1 > 50) print}'

# Slow query detection
grep "SLOW QUERY DETECTED" application.log

# Top 10 slowest operations
grep "duration=" application.log | awk -F'duration=' '{print $2}' | \
  sort -nr | head -10
```

### 5. Concurrent Request Analysis

```bash
# All correlation IDs in last minute
grep "2025-11-16 10:25" application.log | \
  grep -oP '\[.*?\]' | sort -u | wc -l

# Concurrent requests at specific timestamp
grep "2025-11-16 10:25:30" application.log | \
  grep -oP '\[.*?\]' | sort -u
```

## Log Aggregation with ELK

### Elasticsearch Query

Find all logs for correlation ID:

```json
GET /oltp-logs-*/_search
{
  "query": {
    "match": {
      "correlationId": "abc-123"
    }
  },
  "sort": [
    { "timestamp": "asc" }
  ]
}
```

### Kibana Discover

**Search Query**:
```
correlationId: "abc-123"
```

**Advanced Filters**:
```
correlationId: "abc-123" AND level: ERROR
correlationId: "abc-123" AND logger: *AtomicityDemoService*
correlationId: "abc-123" AND message: *transfer*
```

### Logstash Pipeline

```ruby
# logstash.conf
input {
  file {
    path => "/var/log/oltp-demo/application.log"
    start_position => "beginning"
  }
}

filter {
  grok {
    match => {
      "message" => "%{TIMESTAMP_ISO8601:timestamp} \[%{UUID:correlationId}\] %{LOGLEVEL:level}  %{JAVACLASS:logger} - %{GREEDYDATA:log_message}"
    }
  }

  date {
    match => [ "timestamp", "yyyy-MM-dd HH:mm:ss.SSS" ]
    target => "@timestamp"
  }
}

output {
  elasticsearch {
    hosts => ["localhost:9200"]
    index => "oltp-logs-%{+YYYY.MM.dd}"
  }
}
```

## Correlation ID Best Practices

### 1. Always Use Correlation IDs

```java
// GOOD: Correlation ID in every log statement
log.info("Starting transfer: from={}, to={}, correlationId={}",
    fromId, toId, CorrelationIdFilter.getCurrentCorrelationId());

// BAD: Missing correlation ID context
log.info("Starting transfer");
```

### 2. Propagate Across Services

```java
// When calling external services, propagate correlation ID
HttpHeaders headers = new HttpHeaders();
headers.set("X-Correlation-ID", CorrelationIdFilter.getCurrentCorrelationId());

restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), ...);
```

### 3. Include in Error Messages

```java
try {
    performTransfer(from, to, amount);
} catch (Exception e) {
    log.error("Transfer failed: correlationId={}, error={}",
        CorrelationIdFilter.getCurrentCorrelationId(),
        e.getMessage(),
        e
    );
    throw e;
}
```

### 4. Clean Up Async Contexts

```java
@Async
public void processAsync() {
    try {
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();
        CorrelationIdFilter.setCorrelationId(correlationId);

        // Async work here
    } finally {
        CorrelationIdFilter.clearCorrelationId();
    }
}
```

## Common Log Analysis Queries

### Find Failed Transactions

```bash
# All failed transfers
grep "Transfer failed" application.log | grep -oP '\[.*?\]' | sort -u

# Failed transfers with reasons
grep "Transfer failed" application.log | \
  grep -oP 'correlationId=.*?, error=.*' | \
  sort | uniq -c
```

### Performance Hotspots

```bash
# Average transaction duration
grep "Transfer completed successfully" application.log | \
  grep -oP 'duration=\K[0-9]+' | \
  awk '{sum+=$1; count++} END {print sum/count "ms"}'

# Transactions exceeding SLO (>50ms)
grep "Transfer completed successfully" application.log | \
  grep -oP 'duration=\K[0-9]+' | \
  awk '{if ($1 > 50) count++} END {print count " slow transactions"}'
```

### Error Frequency

```bash
# Errors per hour
grep "ERROR" application.log | \
  awk '{print $1, $2}' | cut -d':' -f1 | \
  sort | uniq -c

# Most common error types
grep "ERROR" application.log | \
  grep -oP 'error=.*?(?=,|\s|$)' | \
  sort | uniq -c | sort -nr | head -10
```

## Integration with Distributed Tracing

Correlation IDs complement distributed tracing:

```
┌──────────────────────────────────────┐
│ HTTP Request                         │
│ X-Correlation-ID: abc-123            │
└──────────────────┬───────────────────┘
                   │
       ┌───────────┴──────────┐
       │                      │
       ▼                      ▼
┌─────────────┐      ┌────────────────┐
│ Application │      │  OpenTelemetry │
│    Logs     │      │     Traces     │
│             │      │                │
│ [abc-123]   │      │ TraceID: xyz   │
│ INFO: ...   │◄────►│ SpanID: 123    │
│             │      │ Tags:          │
│             │      │  correlationId │
└─────────────┘      │   = abc-123    │
                     └────────────────┘
```

**Correlation in Jaeger**:
- Search traces by tag: `correlationId=abc-123`
- See logs alongside trace spans
- Navigate from logs to traces and vice versa

## Troubleshooting

### Correlation ID Not Appearing

```bash
# Check MDC configuration in logback.xml
grep "correlationId" src/main/resources/logback-spring.xml

# Verify filter is active
curl -v http://localhost:8080/api/health 2>&1 | grep "X-Correlation-ID"

# Expected output:
# < X-Correlation-ID: 7c9e6679-7425-40de-944b-e07fc1f90ae7
```

### Memory Leaks from MDC

```java
// ALWAYS clean up MDC in finally blocks
try {
    MDC.put("correlationId", id);
    // Work here
} finally {
    MDC.remove("correlationId");  // Prevent memory leak
}
```

### Missing Logs in ELK

```bash
# Check Logstash pipeline is running
curl -XGET 'localhost:9600/_node/stats/pipelines?pretty'

# Verify Elasticsearch index exists
curl -XGET 'localhost:9200/_cat/indices?v' | grep oltp-logs

# Test log ingestion
echo "TEST LOG [abc-123] INFO Test - Testing" >> /var/log/oltp-demo/application.log
```

## Summary

This demonstration shows how to:

✅ Implement correlation ID propagation via HTTP headers
✅ Add correlation IDs to MDC for automatic log tagging
✅ Structure logs for machine and human readability
✅ Trace requests end-to-end across distributed systems
✅ Analyze logs for debugging, performance, and error investigation
✅ Integrate with ELK stack for centralized log aggregation
✅ Correlate logs with distributed traces via correlation IDs

For more demonstrations:
- [Metrics and Monitoring](./metrics.md)
- [Distributed Tracing with Jaeger](./tracing.md)

## References

- [SLF4J MDC Documentation](http://www.slf4j.org/manual.html#mdc)
- [Logback Patterns](http://logback.qos.ch/manual/layouts.html)
- [ELK Stack Documentation](https://www.elastic.co/guide/index.html)
- [Correlation ID Pattern](https://microservices.io/patterns/observability/correlation-id.html)
