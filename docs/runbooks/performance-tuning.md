# Performance Tuning Guide

Optimization techniques for the OLTP Demo application.

## Performance Targets

| Metric | Target | Measurement |
|--------|--------|-------------|
| **Latency (p95)** | < 100ms | 95th percentile response time |
| **Throughput** | > 1000 TPS | Transactions per second |
| **Error rate** | < 0.1% | Failed requests / total requests |
| **Availability** | > 99.9% | Uptime percentage |

## Tuning Checklist

### Application Layer

- [ ] HikariCP pool sized correctly
- [ ] JVM heap sized appropriately
- [ ] Garbage collection tuned
- [ ] Connection timeout configured
- [ ] Thread pool sized
- [ ] Caching enabled where appropriate

### Database Layer

- [ ] Indexes on all foreign keys
- [ ] Composite indexes for common queries
- [ ] Vacuum strategy configured
- [ ] Statistics up to date
- [ ] Connection limit appropriate
- [ ] Shared buffers tuned

### Infrastructure Layer

- [ ] Docker resource limits set
- [ ] Network latency minimized
- [ ] Disk I/O optimized (SSD)
- [ ] Prometheus scrape interval reasonable
- [ ] Log volume manageable

## HikariCP Connection Pool Tuning

### Pool Size Formula

```
pool_size = (core_count * 2) + effective_spindle_count

For SSD (spindle_count = 1):
pool_size = (cores * 2) + 1

Example (4 cores):
pool_size = (4 * 2) + 1 = 9
```

### Configuration

```yaml
# Production configuration
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10
      connection-timeout: 30000      # 30 seconds
      idle-timeout: 600000           # 10 minutes
      max-lifetime: 1800000          # 30 minutes
      leak-detection-threshold: 0    # Disabled in production
```

### Monitoring

```promql
# Pool utilization
hikaricp_connections_active / hikaricp_connections_max

# Connection wait time
hikaricp_connections_pending

# Connection acquisition time
rate(hikaricp_connections_acquire_seconds_sum[5m]) /
rate(hikaricp_connections_acquire_seconds_count[5m])
```

**Alert if**:
- Pool utilization > 80%
- Pending connections > 5
- Acquisition time > 50ms

---

## PostgreSQL Tuning

### Memory Settings

```conf
# For 4GB system
shared_buffers = 1GB              # 25% of RAM
effective_cache_size = 3GB        # 75% of RAM
work_mem = 16MB                   # Per-operation memory
maintenance_work_mem = 256MB      # For VACUUM, CREATE INDEX
```

### Connection Settings

```conf
max_connections = 200             # Match total pool size across instances

# For high-concurrency OLTP
superuser_reserved_connections = 3
```

### WAL Settings

```conf
# Durability (default, safe)
wal_level = replica
fsync = on
synchronous_commit = on           # Wait for WAL flush
wal_buffers = 16MB
checkpoint_timeout = 5min
max_wal_size = 1GB
min_wal_size = 80MB
```

**Trade-off**: For higher performance (less durability):
```conf
synchronous_commit = off          # Don't wait for WAL flush
# Risk: Last ~1 second of transactions may be lost on crash
```

### Query Planner

```conf
random_page_cost = 1.1            # For SSD (default 4.0 for HDD)
effective_io_concurrency = 200    # For SSD
```

### Autovacuum

```conf
autovacuum = on
autovacuum_max_workers = 3
autovacuum_naptime = 1min
autovacuum_vacuum_threshold = 50
autovacuum_analyze_threshold = 50
autovacuum_vacuum_scale_factor = 0.1
autovacuum_analyze_scale_factor = 0.05
```

### Apply Configuration

```bash
# Edit postgresql.conf
docker-compose exec postgres vi /var/lib/postgresql/data/postgresql.conf

# Reload configuration
docker-compose exec postgres psql -U postgres -c "SELECT pg_reload_conf();"

# Restart for some settings
docker-compose restart postgres
```

---

## JVM Tuning

### Heap Sizing

```bash
# Development (small heap)
export MAVEN_OPTS="-Xms512m -Xmx1g"

# Production (larger heap)
export MAVEN_OPTS="-Xms2g -Xmx4g"
```

### GC Tuning (G1GC - default in Java 17)

```bash
# G1GC tuning
export MAVEN_OPTS="\
  -Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=16m \
  -XX:+ParallelRefProcEnabled"
```

### GC Logging

```bash
# Enable GC logs
export MAVEN_OPTS="\
  -Xms2g -Xmx4g \
  -Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=10M"
```

### Monitoring

```promql
# Heap usage
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}

# GC pause time
rate(jvm_gc_pause_seconds_sum[5m]) / rate(jvm_gc_pause_seconds_count[5m])

# GC frequency
rate(jvm_gc_pause_seconds_count[5m])
```

**Target**: GC pause < 100ms, GC time < 5% of total time

---

## Query Optimization

### Identify Slow Queries

```sql
-- Enable pg_stat_statements extension
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Find slowest queries
SELECT
    query,
    calls,
    total_exec_time / 1000 AS total_sec,
    mean_exec_time AS avg_ms,
    max_exec_time AS max_ms
FROM pg_stat_statements
WHERE query NOT LIKE '%pg_stat_statements%'
ORDER BY mean_exec_time DESC
LIMIT 10;
```

### Analyze Query Plans

```sql
-- Get execution plan
EXPLAIN ANALYZE
SELECT * FROM accounts WHERE user_id = 123 AND status = 'ACTIVE';
```

**Look for**:
- `Seq Scan` → Add index
- `Index Scan` → Good!
- High `cost` values → Optimize
- High `actual time` → Bottleneck

### Add Missing Indexes

```sql
-- Check columns used in WHERE clauses
SELECT
    schemaname,
    tablename,
    attname,
    n_distinct,
    correlation
FROM pg_stats
WHERE schemaname = 'public'
    AND n_distinct > 100
ORDER BY n_distinct DESC;

-- Create index
CREATE INDEX CONCURRENTLY idx_name ON table_name (column_name);
```

### Update Statistics

```sql
-- After bulk data load
ANALYZE accounts;
ANALYZE transactions;

-- Or for all tables
ANALYZE;
```

---

## Caching Strategy

### Redis Configuration

```yaml
# application.yml
spring:
  cache:
    type: redis
    redis:
      time-to-live: 600000  # 10 minutes
  redis:
    host: redis
    port: 6379
    timeout: 2000
```

### Cache Annotations

```java
// Cache account lookups
@Cacheable(value = "accounts", key = "#id")
public Account getAccount(Long id) {
    return accountRepository.findById(id).orElseThrow();
}

// Evict cache on update
@CacheEvict(value = "accounts", key = "#account.id")
public Account updateAccount(Account account) {
    return accountRepository.save(account);
}
```

### Cache Metrics

```promql
# Cache hit rate
rate(cache_gets_total{result="hit"}[5m]) /
rate(cache_gets_total[5m])

# Target: > 80% hit rate
```

---

## Load Testing

### Run Benchmarks

```bash
# JMH microbenchmarks
./mvnw test -Dtest=*Benchmark

# Gatling load test
./infrastructure/scripts/run-benchmarks.sh gatling

# Apache Bench (quick test)
ab -n 10000 -c 100 -p transfer.json -T application/json \
   http://localhost:8080/api/demos/acid/atomicity/transfer
```

### Interpret Results

```
Requests per second:    850 [#/sec] (mean)
Time per request:       117.6 [ms] (mean)
Time per request:       1.176 [ms] (mean, across all concurrent requests)

Percentage of the requests served within a certain time (ms)
  50%    100
  66%    110
  75%    120
  80%    125
  90%    140
  95%    160  ← p95 target: < 100ms
  98%    180
  99%    200
 100%    350
```

**Analysis**:
- **Throughput**: 850 TPS (below 1000 target)
- **p95 latency**: 160ms (above 100ms target)
- **Action**: Tune connection pool, check slow queries

---

## Profiling

### CPU Profiling (JMH)

```bash
# Run with profiler
./mvnw test -Dtest=TransferBenchmark \
  -DargLine="-Xms2g -Xmx2g -prof gc"

# View results
cat target/jmh-result.json
```

### Memory Profiling

```bash
# Heap dump
jmap -dump:live,format=b,file=heap.bin <PID>

# Analyze with VisualVM or Eclipse MAT
```

### Database Profiling

```sql
-- Enable timing
SET log_min_duration_statement = 100;  -- Log queries > 100ms

-- View in logs
docker-compose logs postgres | grep "duration:"
```

---

## Performance Testing Scenarios

### Scenario 1: Baseline (No Load)

```bash
# Single request
curl -X POST http://localhost:8080/api/demos/acid/atomicity/transfer \
  -H 'Content-Type: application/json' \
  -d '{"fromAccountId":1,"toAccountId":2,"amount":100}'

# Measure: ~20-50ms expected
```

### Scenario 2: Sustained Load

```bash
# 500 concurrent users, 5 minutes
ab -n 150000 -c 500 -t 300 \
   -p transfer.json -T application/json \
   http://localhost:8080/api/demos/acid/atomicity/transfer

# Target: > 800 TPS, p95 < 100ms
```

### Scenario 3: Spike Test

```bash
# 2000 concurrent users, 1 minute
ab -n 120000 -c 2000 -t 60 \
   -p transfer.json -T application/json \
   http://localhost:8080/api/demos/acid/atomicity/transfer

# Target: No errors, graceful degradation
```

### Scenario 4: Endurance Test

```bash
# 200 concurrent users, 1 hour
ab -n 720000 -c 200 -t 3600 \
   -p transfer.json -T application/json \
   http://localhost:8080/api/demos/acid/atomicity/transfer

# Check: Memory leaks, connection leaks, performance degradation
```

---

## Monitoring Dashboard

### Key Metrics

```promql
# Golden Signals

# 1. Latency
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket[5m]))

# 2. Traffic
rate(http_server_requests_seconds_count[5m])

# 3. Errors
rate(http_server_requests_seconds_count{status=~"5.."}[5m])

# 4. Saturation
hikaricp_connections_active / hikaricp_connections_max
```

### Grafana Dashboard

Import dashboard JSON from: `infrastructure/grafana/dashboards/oltp-overview.json`

Panels:
- Request rate (line chart)
- p95 latency (line chart)
- Error rate (line chart)
- Connection pool utilization (gauge)
- Database connections (gauge)
- JVM heap usage (area chart)
- GC pause time (bar chart)

---

## Performance Improvement Checklist

### Quick Wins (< 1 hour)

- [ ] Enable Redis caching for account lookups
- [ ] Add composite index on `(user_id, status)` for accounts
- [ ] Increase HikariCP pool size to 20
- [ ] Set `random_page_cost = 1.1` for SSD
- [ ] Run `ANALYZE` on all tables

### Medium Effort (1 day)

- [ ] Implement connection pool monitoring alerts
- [ ] Add slow query logging
- [ ] Optimize N+1 query problems with JOIN FETCH
- [ ] Configure JVM GC logging
- [ ] Create custom Grafana dashboards

### Long Term (1 week+)

- [ ] Implement query result caching
- [ ] Add read replicas for reporting queries
- [ ] Partition large tables (transactions, transfer_logs)
- [ ] Implement connection pooling with PgBouncer
- [ ] Set up automated performance regression tests

---

## References

- [PostgreSQL Performance Tuning](https://wiki.postgresql.org/wiki/Performance_Optimization)
- [HikariCP Configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)
- [JVM GC Tuning](https://docs.oracle.com/en/java/javase/17/gctuning/)
- [Spring Boot Performance](https://spring.io/blog/2015/12/10/spring-boot-memory-performance)
