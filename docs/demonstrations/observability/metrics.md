# Metrics and Monitoring with Prometheus

**Demonstration**: Custom business metrics, Prometheus integration, and real-time monitoring

**User Story**: US4 - Comprehensive Observability (T167)

**Functional Requirements**: FR-016

## Overview

This demonstration shows how to instrument a Java application with custom business metrics using Micrometer, export them to Prometheus, and visualize them in Grafana dashboards.

### What You'll Learn

- How to create custom business metrics (counters, timers, gauges)
- Prometheus scraping and metric collection
- PromQL (Prometheus Query Language) for metric analysis
- Grafana dashboard creation for real-time monitoring
- Alerting based on metric thresholds

### Prerequisites

- Application running on `localhost:8080`
- Prometheus running on `localhost:9090` (optional)
- Grafana running on `localhost:3000` (optional)

## Architecture

```
┌─────────────────┐
│  OLTP Demo App  │
│   (Port 8080)   │
│                 │
│  ┌───────────┐  │
│  │ Micrometer│  │  Prometheus format
│  │  Registry │──┼────────────────────┐
│  └───────────┘  │                    │
└─────────────────┘                    ▼
                              ┌──────────────────┐
                              │   Prometheus     │
                              │   (Port 9090)    │
                              │                  │
                              │  - Scrapes /api/ │
                              │    metrics       │
                              │  - Stores TSDB   │
                              └──────────────────┘
                                       │
                                       ▼
                              ┌──────────────────┐
                              │     Grafana      │
                              │   (Port 3000)    │
                              │                  │
                              │  - Dashboards    │
                              │  - Alerts        │
                              └──────────────────┘
```

## Accessing Metrics

### Direct Access (Without Prometheus)

The application exposes metrics at `/api/metrics` in Prometheus text format:

```bash
# Get all metrics
curl http://localhost:8080/api/metrics

# Sample output:
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
# jvm_memory_used_bytes{area="heap",id="G1 Eden Space",} 5.242880E7
# jvm_memory_used_bytes{area="heap",id="G1 Old Gen",} 1.2345678E7
# ...
```

### Prometheus Configuration

Configure Prometheus to scrape the application:

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'oltp-demo'
    metrics_path: '/api/metrics'
    static_configs:
      - targets: ['localhost:8080']
    scrape_interval: 10s
```

Then access Prometheus UI at `http://localhost:9090`.

## Custom Business Metrics

### Transaction Throughput

**Metric**: `oltp_transaction_throughput_total`

**Type**: Counter

**Labels**: `transaction_type`, `status`, `service`, `correlationId`

**Description**: Total number of transactions processed

#### PromQL Queries

```promql
# Current throughput (requests per second)
rate(oltp_transaction_throughput_total{status="success"}[1m])

# Throughput by transaction type
sum by (transaction_type) (rate(oltp_transaction_throughput_total{status="success"}[1m]))

# Success rate percentage
sum(rate(oltp_transaction_throughput_total{status="success"}[1m]))
/
sum(rate(oltp_transaction_throughput_total[1m])) * 100

# Failed transactions per second
rate(oltp_transaction_throughput_total{status="failure"}[1m])

# Total transactions in last 5 minutes
sum(increase(oltp_transaction_throughput_total[5m]))
```

#### Example Dashboard Panel

```json
{
  "title": "Transaction Throughput",
  "targets": [{
    "expr": "sum by (transaction_type) (rate(oltp_transaction_throughput_total{status=\"success\"}[1m]))",
    "legendFormat": "{{transaction_type}}"
  }]
}
```

### Transaction Latency

**Metric**: `oltp_transaction_latency_seconds`

**Type**: Timer (Histogram)

**Labels**: `transaction_type`, `service`, `correlationId`

**Description**: Transaction execution latency distribution with percentiles (p50, p95, p99)

#### PromQL Queries

```promql
# p50 (median) latency in milliseconds
histogram_quantile(0.50,
  sum(rate(oltp_transaction_latency_seconds_bucket[1m])) by (le, transaction_type)
) * 1000

# p95 latency by transaction type
histogram_quantile(0.95,
  sum(rate(oltp_transaction_latency_seconds_bucket[1m])) by (le, transaction_type)
) * 1000

# p99 latency for TRANSFER transactions
histogram_quantile(0.99,
  sum(rate(oltp_transaction_latency_seconds_bucket{transaction_type="TRANSFER"}[1m])) by (le)
) * 1000

# Average latency
rate(oltp_transaction_latency_seconds_sum[1m]) / rate(oltp_transaction_latency_seconds_count[1m]) * 1000

# SLO violation: transactions exceeding 50ms (p95)
(histogram_quantile(0.95,
  sum(rate(oltp_transaction_latency_seconds_bucket[1m])) by (le, transaction_type)
) > 0.050) * 1000
```

#### SLO Dashboard Panel

```json
{
  "title": "Transaction Latency SLO (p95 < 50ms)",
  "targets": [{
    "expr": "histogram_quantile(0.95, sum(rate(oltp_transaction_latency_seconds_bucket[1m])) by (le)) * 1000",
    "refId": "A"
  }],
  "thresholds": [
    {"value": 50, "color": "red"}
  ]
}
```

### Error Rates

**Metric**: `oltp_error_count_total`

**Type**: Counter

**Labels**: `error_type`, `service`, `severity`, `correlationId`

**Description**: Total errors by type and service

#### PromQL Queries

```promql
# Errors per second
rate(oltp_error_count_total[1m])

# Errors by type
sum by (error_type) (rate(oltp_error_count_total[1m]))

# High-severity errors only
rate(oltp_error_count_total{severity="ERROR"}[1m])

# Error rate percentage (errors / total requests)
sum(rate(oltp_error_count_total[1m]))
/
sum(rate(http_server_requests_seconds_count[1m])) * 100

# Top 5 error types
topk(5, sum by (error_type) (increase(oltp_error_count_total[1h])))
```

#### Alert Rule

```yaml
# prometheus-alerts.yml
groups:
  - name: oltp_errors
    rules:
      - alert: HighErrorRate
        expr: rate(oltp_error_count_total[5m]) > 10
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "High error rate detected"
          description: "Error rate is {{ $value }} errors/sec (threshold: 10)"
```

### Connection Pool Metrics

**Metrics**: `oltp_connection_pool_active`, `oltp_connection_pool_idle`, `oltp_connection_pool_pending`

**Type**: Gauge

**Description**: HikariCP connection pool health

#### PromQL Queries

```promql
# Active connections
oltp_connection_pool_active

# Idle connections
oltp_connection_pool_idle

# Total connections
oltp_connection_pool_total

# Pending thread requests (pool exhaustion indicator)
oltp_connection_pool_pending

# Pool utilization percentage
(oltp_connection_pool_active / oltp_connection_pool_total) * 100

# Connection wait time (p95)
histogram_quantile(0.95,
  sum(rate(oltp_connection_pool_wait_time_seconds_bucket[1m])) by (le)
) * 1000
```

#### Pool Exhaustion Alert

```yaml
- alert: ConnectionPoolExhaustion
  expr: oltp_connection_pool_pending > 5
  for: 1m
  labels:
    severity: warning
  annotations:
    summary: "Connection pool has pending threads"
    description: "{{ $value }} threads waiting for connections"
```

### Cache Metrics

**Metric**: `oltp_cache_access_total`

**Type**: Counter

**Labels**: `cache`, `status` (hit/miss)

**Description**: Cache access statistics

#### PromQL Queries

```promql
# Cache hit rate percentage
sum(rate(oltp_cache_access_total{status="hit"}[1m]))
/
sum(rate(oltp_cache_access_total[1m])) * 100

# Cache hits per second
rate(oltp_cache_access_total{status="hit"}[1m])

# Cache misses per second
rate(oltp_cache_access_total{status="miss"}[1m])

# Cache efficiency by cache name
sum by (cache) (rate(oltp_cache_access_total{status="hit"}[1m]))
/
sum by (cache) (rate(oltp_cache_access_total[1m])) * 100
```

### Slow Query Metrics

**Metric**: `oltp_slow_query_count_total`

**Type**: Counter

**Labels**: `query_type`, `threshold_ms`

**Description**: Number of slow queries detected

#### PromQL Queries

```promql
# Slow queries per second
rate(oltp_slow_query_count_total[1m])

# Slow queries by type
sum by (query_type) (rate(oltp_slow_query_count_total[1m]))

# Slow query duration (p95)
histogram_quantile(0.95,
  sum(rate(oltp_slow_query_duration_seconds_bucket[1m])) by (le, query_type)
) * 1000

# Total slow queries in last hour
sum(increase(oltp_slow_query_count_total[1h]))
```

## JVM Metrics

Micrometer automatically exposes JVM metrics:

### Memory

```promql
# Heap usage percentage
(sum(jvm_memory_used_bytes{area="heap"})
 /
 sum(jvm_memory_max_bytes{area="heap"})) * 100

# Heap used by generation
jvm_memory_used_bytes{area="heap"}

# Non-heap memory (metaspace, code cache)
jvm_memory_used_bytes{area="nonheap"}

# Memory allocation rate
rate(jvm_gc_memory_allocated_bytes_total[1m])
```

### Garbage Collection

```promql
# GC collections per second
rate(jvm_gc_pause_seconds_count[1m])

# GC pause time (ms/sec)
rate(jvm_gc_pause_seconds_sum[1m]) * 1000

# GC pause time by action
sum by (action, cause) (rate(jvm_gc_pause_seconds_sum[1m])) * 1000

# Time spent in GC (percentage)
rate(jvm_gc_pause_seconds_sum[1m]) / rate(jvm_gc_pause_seconds_count[1m]) * 100
```

### Threads

```promql
# Live threads
jvm_threads_live_threads

# Peak threads
jvm_threads_peak_threads

# Daemon threads
jvm_threads_daemon_threads

# Thread states
jvm_threads_states_threads
```

## HTTP Server Metrics

Automatically captured by Spring Boot Actuator:

```promql
# HTTP request rate
rate(http_server_requests_seconds_count[1m])

# HTTP request latency (p95)
histogram_quantile(0.95,
  sum(rate(http_server_requests_seconds_bucket[1m])) by (le, uri, method)
) * 1000

# Requests by status code
sum by (status) (rate(http_server_requests_seconds_count[1m]))

# Slow endpoints (p99 > 500ms)
histogram_quantile(0.99,
  sum(rate(http_server_requests_seconds_bucket[1m])) by (le, uri)
) > 0.5
```

## HikariCP Metrics

Automatically exposed by HikariCP:

```promql
# Connection acquisition time (p95)
histogram_quantile(0.95,
  sum(rate(hikaricp_connections_acquire_seconds_bucket[1m])) by (le)
) * 1000

# Connection usage time
histogram_quantile(0.95,
  sum(rate(hikaricp_connections_usage_seconds_bucket[1m])) by (le)
) * 1000

# Connection creation time
histogram_quantile(0.95,
  sum(rate(hikaricp_connections_creation_seconds_bucket[1m])) by (le)
) * 1000

# Connection timeouts
rate(hikaricp_connections_timeout_total[1m])
```

## Grafana Dashboard Import

Import the pre-configured dashboards:

```bash
# From project root
ls infrastructure/docker/grafana/dashboards/

# Available dashboards:
# - oltp-overview.json: Transaction metrics
# - database-metrics.json: PostgreSQL and HikariCP
# - jvm-metrics.json: JVM heap, GC, threads
```

### Import Steps

1. Open Grafana: `http://localhost:3000`
2. Navigate to **Dashboards** → **Import**
3. Upload JSON file or paste contents
4. Select Prometheus datasource
5. Click **Import**

## Alerting Examples

### Prometheus Alert Rules

```yaml
groups:
  - name: oltp_slo
    rules:
      # Transaction latency SLO
      - alert: TransactionLatencyHigh
        expr: histogram_quantile(0.95, sum(rate(oltp_transaction_latency_seconds_bucket[5m])) by (le)) > 0.050
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Transaction latency exceeds SLO"
          description: "p95 latency is {{ $value | humanizeDuration }} (SLO: 50ms)"

      # Success rate SLO
      - alert: TransactionSuccessRateLow
        expr: |
          sum(rate(oltp_transaction_throughput_total{status="success"}[5m]))
          /
          sum(rate(oltp_transaction_throughput_total[5m])) < 0.99
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Transaction success rate below SLO"
          description: "Success rate is {{ $value | humanizePercentage }} (SLO: 99%)"

      # Pool exhaustion
      - alert: ConnectionPoolExhausted
        expr: oltp_connection_pool_pending > 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Connection pool has pending threads"
          description: "{{ $value }} threads waiting for database connections"

      # Memory pressure
      - alert: MemoryUsageHigh
        expr: (sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"})) > 0.90
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "JVM heap usage is high"
          description: "Heap usage is {{ $value | humanizePercentage }} (threshold: 90%)"
```

## Best Practices

### Metric Naming

- Use lowercase with underscores: `oltp_transaction_latency`
- Suffix counters with `_total`: `oltp_error_count_total`
- Suffix timers with `_seconds`: `transaction_duration_seconds`
- Use units in metric names: `_bytes`, `_seconds`, `_percent`

### Labels

- Keep cardinality low (avoid user IDs, timestamps)
- Use consistent label names across metrics
- Common labels: `application`, `environment`, `service`
- Correlation ID is tagged but indexed separately

### Retention

- Prometheus default: 15 days
- Long-term storage: Use Thanos or Cortex
- Aggregation: Pre-aggregate high-resolution data

### Sampling

- High-frequency metrics: Sample at source
- Histogram buckets: Choose appropriate boundaries
- P50, P95, P99 sufficient for most latency tracking

## Troubleshooting

### Metrics Not Appearing

```bash
# Check metrics endpoint is accessible
curl http://localhost:8080/api/metrics | grep oltp

# Verify Prometheus can scrape
# In Prometheus UI: Status → Targets
# Should show oltp-demo target as UP

# Check Prometheus logs
docker logs prometheus 2>&1 | grep -i error
```

### High Cardinality Issues

```bash
# Find high-cardinality metrics
# In Prometheus UI: Status → TSDB Status
# Look for metrics with many series

# Example: Correlation ID creates high cardinality
# Solution: Sample or aggregate correlation IDs
```

### Grafana Not Showing Data

```bash
# Verify datasource connection
# Grafana → Configuration → Data Sources → Prometheus
# Click "Test" - should return "Data source is working"

# Check query syntax in panel
# Use Query Inspector to see actual PromQL query

# Verify time range matches data retention
```

## Summary

This demonstration shows how to:

✅ Instrument Java applications with Micrometer
✅ Create custom business metrics (counters, timers, gauges)
✅ Export metrics in Prometheus format
✅ Write PromQL queries for metric analysis
✅ Build Grafana dashboards for visualization
✅ Configure alerts based on SLO thresholds
✅ Monitor JVM, HTTP, database, and cache performance

For more demonstrations:
- [Logging with Correlation IDs](./logging.md)
- [Distributed Tracing with Jaeger](./tracing.md)

## References

- [Micrometer Documentation](https://micrometer.io/docs)
- [Prometheus Query Language](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- [Grafana Dashboards](https://grafana.com/docs/grafana/latest/dashboards/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
