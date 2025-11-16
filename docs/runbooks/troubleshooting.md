# Troubleshooting Guide

Common issues and solutions for the OLTP Demo application.

## Quick Diagnostics

```bash
# Check all services
docker-compose -f infrastructure/docker/docker-compose.yml ps

# View application logs
docker-compose -f infrastructure/docker/docker-compose.yml logs -f app

# Check database connectivity
docker-compose -f infrastructure/docker/docker-compose.yml exec postgres pg_isready

# Check Redis
docker-compose -f infrastructure/docker/docker-compose.yml exec redis redis-cli ping
```

## Common Issues

### 1. Application Won't Start

**Symptom**: Application fails to start with connection errors

**Possible Causes**:
- PostgreSQL not ready
- Port conflicts (8080 already in use)
- Missing environment variables

**Solutions**:

```bash
# Check if PostgreSQL is ready
docker-compose exec postgres pg_isready -U postgres

# Check port availability
lsof -i :8080  # macOS/Linux
netstat -ano | findstr :8080  # Windows

# Check application logs
./mvnw spring-boot:run 2>&1 | tee app.log

# Reset everything
./infrastructure/scripts/clean-reset.sh --force
./infrastructure/scripts/setup.sh
```

---

### 2. Connection Pool Exhausted

**Symptom**: `HikariPool - Connection is not available` errors

**Cause**: Connection leaks or insufficient pool size

**Diagnosis**:
```bash
# Check HikariCP metrics
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active
curl http://localhost:8080/actuator/metrics/hikaricp.connections.pending
```

**Solutions**:

```yaml
# Increase pool size (application.yml)
spring:
  datasource:
    hikari:
      maximum-pool-size: 50  # Increase from default 20

# Enable leak detection (development only)
spring:
  datasource:
    hikari:
      leak-detection-threshold: 10000  # 10 seconds
```

**Find leaks**:
```bash
# Look for stack traces in logs
grep "Connection leak detection" logs/application.log
```

---

### 3. Slow Queries

**Symptom**: High latency (p95 > 100ms)

**Diagnosis**:
```sql
-- Find slow queries (PostgreSQL)
SELECT
    pid,
    now() - pg_stat_activity.query_start AS duration,
    query,
    state
FROM pg_stat_activity
WHERE (now() - pg_stat_activity.query_start) > interval '5 seconds'
    AND state != 'idle';
```

**Solutions**:

1. **Add missing indexes**:
```sql
-- Check missing indexes
SELECT
    schemaname,
    tablename,
    attname,
    n_distinct,
    correlation
FROM pg_stats
WHERE schemaname = 'public'
    AND n_distinct > 100;  -- High cardinality columns
```

2. **Analyze query plan**:
```sql
EXPLAIN ANALYZE
SELECT * FROM accounts WHERE user_id = 123 AND status = 'ACTIVE';
```

3. **Update statistics**:
```sql
ANALYZE accounts;
ANALYZE transactions;
```

---

### 4. Deadlocks

**Symptom**: `DeadlockDetectedException` (SQL state 40P01)

**Diagnosis**:
```bash
# View recent deadlocks in logs
docker-compose logs postgres | grep -A 10 "deadlock detected"
```

**Solutions**:

1. **Consistent lock ordering**:
```java
// Always lock in same order (by ID)
Long firstId = Math.min(fromId, toId);
Long secondId = Math.max(fromId, toId);
```

2. **Reduce transaction duration**:
```java
// Move external API calls outside transaction
@Transactional
public void transfer(...) {
    // Lock and update only
}
// Call external service after commit
```

3. **Use optimistic locking** (if appropriate):
```java
@Version
private Long version;  // Spring handles OptimisticLockException
```

---

### 5. High Memory Usage

**Symptom**: OutOfMemoryError or slow GC

**Diagnosis**:
```bash
# Check heap usage
curl http://localhost:8080/actuator/metrics/jvm.memory.used

# View GC activity
curl http://localhost:8080/actuator/metrics/jvm.gc.pause
```

**Solutions**:

1. **Increase heap size**:
```bash
# Edit pom.xml or run command
export MAVEN_OPTS="-Xms1g -Xmx2g"
./mvnw spring-boot:run
```

2. **Check for memory leaks**:
```bash
# Heap dump
jmap -dump:live,format=b,file=heap.bin <PID>

# Analyze with VisualVM or Eclipse MAT
```

3. **Reduce connection pool size** (if too large):
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10  # Reduce from 20
```

---

### 6. Database Disk Full

**Symptom**: `ERROR: could not extend file... No space left on device`

**Diagnosis**:
```bash
# Check disk usage
docker-compose exec postgres df -h

# Check database size
docker-compose exec postgres psql -U postgres -d oltpdemo -c "
SELECT
    pg_database.datname,
    pg_size_pretty(pg_database_size(pg_database.datname)) AS size
FROM pg_database
ORDER BY pg_database_size(pg_database.datname) DESC;
"
```

**Solutions**:

1. **Clean up old data**:
```sql
-- Delete old sessions
DELETE FROM sessions WHERE expires_at < NOW() - INTERVAL '7 days';

-- Truncate test data
TRUNCATE TABLE transfer_logs CASCADE;
TRUNCATE TABLE transactions CASCADE;
```

2. **Vacuum and analyze**:
```sql
VACUUM FULL ANALYZE;
```

3. **Increase Docker volume size** (if using Docker Desktop)

---

### 7. Prometheus Not Scraping Metrics

**Symptom**: No metrics in Grafana dashboards

**Diagnosis**:
```bash
# Check if actuator endpoint is accessible
curl http://localhost:8080/actuator/prometheus

# Check Prometheus targets
curl http://localhost:9090/api/v1/targets | jq
```

**Solutions**:

1. **Verify actuator configuration**:
```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
```

2. **Check Prometheus configuration**:
```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'spring-boot-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['app:8080']  # Use Docker service name
```

3. **Restart Prometheus**:
```bash
docker-compose restart prometheus
```

---

### 8. Flyway Migration Failures

**Symptom**: `FlywayException: Validate failed` or checksum mismatch

**Solutions**:

1. **Development: Repair and re-migrate**:
```bash
./mvnw flyway:repair
./mvnw flyway:migrate
```

2. **Production: Never modify existing migrations**
   - Create new migration (V7, V8, etc.)
   - Never change V1-V6 after deployment

3. **Reset database completely** (development only):
```bash
./infrastructure/scripts/clean-reset.sh --force
```

---

## Performance Monitoring

### Key Metrics to Watch

```promql
# Request rate
rate(http_server_requests_seconds_count[1m])

# p95 latency
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[1m]))

# Error rate
rate(http_server_requests_seconds_count{status=~"5.."}[1m])

# Connection pool utilization
hikaricp_connections_active / hikaricp_connections_max

# Database connection count
sum(pg_stat_activity_count)
```

### Alerts to Configure

1. **High error rate**: > 5% of requests failing
2. **High latency**: p95 > 1 second
3. **Connection pool exhaustion**: pending connections > 5
4. **Database connections**: > 80% of max_connections
5. **Disk usage**: > 80% full

## Emergency Procedures

### Application Unresponsive

```bash
# 1. Check if process is running
ps aux | grep java

# 2. Get thread dump
kill -3 <PID>
# Thread dump written to stdout

# 3. Get heap dump
jmap -dump:live,format=b,file=heap.bin <PID>

# 4. Restart application
./mvnw spring-boot:stop
./mvnw spring-boot:run
```

### Database Unresponsive

```bash
# 1. Check connections
docker-compose exec postgres psql -U postgres -d oltpdemo -c "
SELECT count(*) FROM pg_stat_activity;
"

# 2. Kill long-running queries
docker-compose exec postgres psql -U postgres -d oltpdemo -c "
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE state = 'active'
    AND (now() - query_start) > interval '5 minutes';
"

# 3. Restart PostgreSQL
docker-compose restart postgres
```

## Getting Help

1. **Check logs**: Always start with application and database logs
2. **Search issues**: GitHub issues may have similar problems
3. **Enable debug logging**: Set `logging.level.com.oltp.demo=DEBUG`
4. **Collect diagnostics**: Logs, thread dumps, heap dumps
5. **Create minimal reproduction**: Simplify to smallest failing case

## References

- [Spring Boot Common Application Properties](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html)
- [PostgreSQL Error Codes](https://www.postgresql.org/docs/current/errcodes-appendix.html)
- [HikariCP Configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)
