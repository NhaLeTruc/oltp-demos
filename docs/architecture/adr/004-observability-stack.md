# ADR 004: Observability Stack (Micrometer + Prometheus + Grafana + Jaeger)

## Status

**Accepted** - 2025-11-16

## Context

OLTP systems require comprehensive observability to understand performance, detect issues, and demonstrate system behavior. We need a complete observability stack covering:

1. **Metrics**: Quantitative measurements (request rate, latency, error rate)
2. **Logs**: Structured event records for debugging
3. **Traces**: Request flow across components
4. **Dashboards**: Visual representation of system health

## Decision

We will use the following observability stack:

- **Micrometer**: Metrics abstraction layer
- **Prometheus**: Metrics collection and storage
- **Grafana**: Dashboards and visualization
- **Jaeger**: Distributed tracing
- **Logback**: Structured logging with JSON format

## Rationale

### Metrics: Micrometer + Prometheus

**Micrometer**:
- Vendor-neutral metrics API (like SLF4J for logging)
- Built into Spring Boot Actuator
- Support for multiple backends (Prometheus, InfluxDB, Datadog, etc.)
- Timer, Counter, Gauge abstractions

**Prometheus**:
- **Pull-based model**: Scrapes metrics from application
- **Time-series database**: Optimized for metrics storage
- **PromQL**: Powerful query language for aggregations
- **Alerting**: Built-in alerting rules
- **Service discovery**: Auto-discovers services in Docker/Kubernetes

**Why not alternatives**:
- **InfluxDB**: Push-based, requires agent, less popular
- **Datadog**: Proprietary, costly, overkill for demos
- **StatsD + Graphite**: Outdated, less features

### Dashboards: Grafana

**Grafana**:
- **Multi-source**: Connects to Prometheus, PostgreSQL, etc.
- **Pre-built dashboards**: Community dashboards for Spring Boot, PostgreSQL, HikariCP
- **Alerting**: Visual alert management
- **Query builder**: Easy to create custom dashboards
- **Free and open-source**: No licensing costs

**Why not alternatives**:
- **Kibana**: Designed for Elasticsearch, not optimized for Prometheus
- **Prometheus UI**: Basic, not suitable for production dashboards

### Tracing: Jaeger

**Jaeger**:
- **OpenTelemetry compatible**: Industry standard tracing format
- **Distributed tracing**: Follows requests across services
- **UI**: Clean interface for trace visualization
- **Sampling**: Configurable sampling rates
- **Storage backends**: In-memory, Cassandra, Elasticsearch

**Why not alternatives**:
- **Zipkin**: Less active development, smaller community
- **AWS X-Ray**: Cloud-specific, not portable
- **New Relic**: Proprietary, costly

### Logging: Logback + JSON

**Logback**:
- **SLF4J backend**: Standard Java logging framework
- **Spring Boot default**: Zero configuration
- **JSON encoding**: Structured logs for parsing
- **MDC (Mapped Diagnostic Context)**: Correlation ID tracking

**JSON Format**:
- Machine-readable
- Easy to ingest into log aggregators (ELK, Loki)
- Supports structured fields (correlation_id, user_id, etc.)

## Architecture

```
┌─────────────────────────────────────────────────────┐
│ Application (Spring Boot)                           │
│                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────┐ │
│  │ Micrometer   │  │ Logback      │  │ OpenTelemetry│
│  │ (Metrics)    │  │ (Logs)       │  │ (Traces)   │ │
│  └──────┬───────┘  └──────┬───────┘  └─────┬─────┘ │
│         │                 │                 │       │
└─────────┼─────────────────┼─────────────────┼───────┘
          │                 │                 │
          ▼                 ▼                 ▼
   ┌─────────────┐   ┌───────────┐   ┌──────────────┐
   │ Prometheus  │   │ Stdout    │   │    Jaeger    │
   │ (Pull /metrics) │ (JSON logs) │   │ (Trace collector)│
   └──────┬──────┘   └───────────┘   └──────┬───────┘
          │                                  │
          ▼                                  │
   ┌─────────────┐                           │
   │   Grafana   │◄──────────────────────────┘
   │ (Dashboards) │
   └─────────────┘
```

## Configuration

### Metrics: application.yml

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: oltp-demo
      environment: ${ENVIRONMENT:dev}
```

### Prometheus: prometheus.yml

```yaml
scrape_configs:
  - job_name: 'spring-boot-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['app:8080']
    scrape_interval: 5s
```

### Tracing: application.yml

```yaml
management:
  tracing:
    sampling:
      probability: 1.0  # 100% sampling for demos
  zipkin:
    tracing:
      endpoint: http://jaeger:9411/api/v2/spans
```

### Logging: logback-spring.xml

```xml
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdcKeyName>correlationId</includeMdcKeyName>
        <includeMdcKeyName>userId</includeMdcKeyName>
        <includeMdcKeyName>transactionId</includeMdcKeyName>
    </encoder>
</appender>
```

## Metrics Exposed

### Application Metrics

```promql
# Request rate
rate(http_server_requests_seconds_count[1m])

# Request latency (p95)
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket[1m]))

# Error rate
rate(http_server_requests_seconds_count{status=~"5.."}[1m])
```

### Database Metrics

```promql
# HikariCP connection pool
hikaricp_connections_active
hikaricp_connections_idle
hikaricp_connections_pending

# Database query time
rate(spring_data_repository_invocations_seconds_sum[1m]) /
rate(spring_data_repository_invocations_seconds_count[1m])
```

### JVM Metrics

```promql
# Heap memory usage
jvm_memory_used_bytes{area="heap"}

# GC time
rate(jvm_gc_pause_seconds_sum[1m])

# Thread count
jvm_threads_live
```

### Custom Metrics

```java
@Timed(value = "transfer.duration",
       description = "Time taken for transfer operation")
public void transfer(Long from, Long to, BigDecimal amount) {
    // ...
}

meterRegistry.counter("transfer.count",
    "status", "success").increment();
```

## Grafana Dashboards

### Pre-built Dashboards

1. **JVM (Micrometer)**: Dashboard ID 4701
2. **Spring Boot Statistics**: Dashboard ID 12900
3. **HikariCP**: Dashboard ID 11663
4. **PostgreSQL**: Dashboard ID 9628

### Custom Dashboards

- **OLTP Overview**: Request rate, latency, error rate
- **Transaction Performance**: Transfer latency, success rate
- **Concurrency**: Active transactions, lock wait time
- **Database Health**: Connection pool, query performance

## Tracing with Jaeger

### Trace Context Propagation

```java
@RestController
public class TransferController {

    @PostMapping("/api/transfer")
    public ResponseEntity<TransferResult> transfer(
        @RequestBody TransferRequest request
    ) {
        // Span automatically created by Spring Boot
        // Includes: http.method, http.url, http.status_code
        return transferService.executeTransfer(request);
    }
}

@Service
public class TransferService {

    @NewSpan("transfer.execute")
    public TransferResult executeTransfer(TransferRequest request) {
        // Custom span with tags
        Span span = tracer.currentSpan();
        span.tag("transfer.amount", request.getAmount().toString());
        span.tag("transfer.from", request.getFromAccountId().toString());
        // ...
    }
}
```

### Trace Visualization

Jaeger UI shows:
- Request timeline across services
- Database query durations
- External API calls
- Error stack traces

## Log Aggregation

### Structured Logs

```json
{
  "@timestamp": "2025-11-16T10:15:23.456Z",
  "level": "INFO",
  "logger_name": "com.oltp.demo.service.TransferService",
  "message": "Transfer completed successfully",
  "thread_name": "http-nio-8080-exec-1",
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": 123,
  "transactionId": 456,
  "amount": 100.00,
  "duration_ms": 25
}
```

### Log Queries (Loki PromQL)

```promql
# Errors in last hour
{job="spring-boot-app"} |= "ERROR" | json

# Slow transactions (>1s)
{job="spring-boot-app"} | json | duration_ms > 1000

# Transactions by user
{job="spring-boot-app"} | json | userId="123"
```

## Alerting Rules

### Prometheus Alerts

```yaml
groups:
  - name: oltp_alerts
    rules:
      - alert: HighErrorRate
        expr: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.05
        annotations:
          summary: "High error rate detected"

      - alert: HighLatency
        expr: histogram_quantile(0.95,
                rate(http_server_requests_seconds_bucket[5m])) > 1.0
        annotations:
          summary: "p95 latency > 1 second"

      - alert: ConnectionPoolExhaustion
        expr: hikaricp_connections_pending > 5
        annotations:
          summary: "Connection pool exhausted"
```

## Consequences

### Positive

1. **Comprehensive observability**: Metrics, logs, and traces in one stack
2. **Industry-standard tools**: Skills transferable to production systems
3. **Open-source**: No licensing costs
4. **Docker Compose**: Easy local setup
5. **Educational**: Clear examples of observability best practices

### Negative

1. **Resource overhead**: Prometheus + Grafana + Jaeger = ~500MB RAM
2. **Complexity**: Multiple tools to learn
3. **Configuration**: Requires initial setup (Docker Compose provided)

### Mitigations

- **Docker Compose**: Pre-configured stack in `infrastructure/docker/`
- **Documentation**: Step-by-step guides in `docs/runbooks/`
- **Resource limits**: Configure appropriate memory limits for containers

## Alternatives Considered

| Alternative | Pros | Cons | Verdict |
|-------------|------|------|---------|
| **ELK Stack (Elasticsearch, Logstash, Kibana)** | Full-text search, powerful queries | Resource-heavy, complex | Rejected - Overkill for metrics |
| **Datadog** | All-in-one SaaS, excellent UX | Proprietary, costly | Rejected - Not free |
| **New Relic** | Excellent APM, easy setup | Proprietary, costly | Rejected - Not free |
| **AppDynamics** | Deep tracing, business metrics | Enterprise, costly | Rejected - Not accessible |
| **Lightstep** | Modern tracing, OpenTelemetry native | SaaS-only, costly | Rejected - Not self-hosted |

## References

- [Micrometer Documentation](https://micrometer.io/docs)
- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [OpenTelemetry](https://opentelemetry.io/)

## Review

- **Decision date**: 2025-11-16
- **Participants**: Technical team
- **Next review**: After observability dashboard creation (Q1 2026)
