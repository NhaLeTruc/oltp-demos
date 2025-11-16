# ADR 003: HikariCP for Connection Pooling

## Status

**Accepted** - 2025-11-16

## Context

PostgreSQL has a relatively low default connection limit (`max_connections = 100`). Creating a new database connection is expensive (~10-50ms), which would severely impact OLTP performance demonstrations.

We need a connection pool that:

1. **Performs well**: Minimal overhead for high-concurrency workloads
2. **Configurable**: Easy to tune for demonstrations
3. **Observable**: Provides metrics for monitoring
4. **Reliable**: Battle-tested in production environments
5. **Educational**: Clear behavior for teaching connection management

## Decision

We will use **HikariCP** as the primary connection pool for all database connections.

## Rationale

### HikariCP Strengths

**1. Best-in-class Performance**:
- **Zero-overhead**: Fastest connection pool in Java ecosystem
- **Optimized algorithms**: ConcurrentBag for managing connections
- **Minimal lock contention**: Lock-free techniques where possible
- **Benchmarks**: Consistently outperforms alternatives (C3P0, DBCP2, Tomcat Pool)

**2. Spring Boot Default**:
- Built-in since Spring Boot 2.0
- Auto-configured with sensible defaults
- Minimal setup required

**3. Rich Configuration**:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20           # Max connections
      minimum-idle: 10                # Min idle connections
      connection-timeout: 30000       # 30 seconds
      idle-timeout: 600000            # 10 minutes
      max-lifetime: 1800000           # 30 minutes
      leak-detection-threshold: 60000 # 60 seconds
```

**4. Leak Detection**:
- Detects connections not returned to pool
- Configurable threshold: `leak-detection-threshold`
- Logs stack traces for debugging

**5. Health Checks**:
- `connection-test-query`: Validate connections before use
- Health check intervals configurable
- Automatic removal of stale connections

**6. Metrics and Monitoring**:
- Exposes JMX MBeans
- Integrates with Micrometer (Spring Boot Actuator)
- Metrics: active connections, idle connections, pending threads, connection acquisition time

**7. Reliability Features**:
- **Connection validation**: Ensures connections are alive before use
- **Max lifetime**: Prevents long-lived connection issues
- **Leak detection**: Finds connection leaks in application code

### Comparison with Alternatives

| Feature | HikariCP | Apache DBCP2 | C3P0 | Tomcat Pool |
|---------|----------|--------------|------|-------------|
| **Performance** | Excellent | Good | Fair | Good |
| **Configuration** | Simple | Complex | Complex | Moderate |
| **Metrics** | Built-in | Limited | Limited | Moderate |
| **Leak detection** | Yes | No | No | Yes |
| **Active development** | Yes | Yes | Stalled | Yes |
| **Spring Boot default** | Yes (2.0+) | No | No | No |
| **Lightweight** | Yes | No | No | Yes |

### Why Not Alternatives?

**Apache Commons DBCP2**:
- ❌ Slower than HikariCP
- ❌ More complex configuration
- ✅ Mature and stable
- **Verdict**: Performance critical for OLTP demos

**C3P0**:
- ❌ Development has stalled
- ❌ Slower than HikariCP
- ❌ Complex XML configuration
- **Verdict**: Outdated

**Tomcat JDBC Pool**:
- ✅ Good performance
- ✅ Leak detection
- ❌ Not Spring Boot default
- ❌ Tomcat-specific
- **Verdict**: No advantages over HikariCP

**PgBouncer** (Server-side pooler):
- ✅ Extremely efficient (C implementation)
- ✅ Supports connection multiplexing
- ❌ External dependency (additional container)
- ❌ Adds complexity to demos
- ❌ Harder to demonstrate application-side pooling
- **Verdict**: Excellent for production, but overkill for demos

## Configuration for OLTP Demonstrations

### Development Profile

```yaml
# application-dev.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 5000
      idle-timeout: 300000
      leak-detection-threshold: 30000
```

**Rationale**:
- Small pool size (10) to demonstrate connection exhaustion scenarios
- Short timeouts to quickly show errors
- Aggressive leak detection for development

### Load Testing Profile

```yaml
# application-loadtest.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
      minimum-idle: 20
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 0  # Disabled for performance
```

**Rationale**:
- Larger pool (50) for high concurrency (1000 users)
- Longer timeouts to handle spikes
- Leak detection disabled to avoid overhead

## Consequences

### Positive

1. **Zero configuration**: Works out-of-the-box with Spring Boot
2. **Performance**: Minimal overhead for connection acquisition (~1μs)
3. **Clear demonstrations**: Easy to show connection pool exhaustion, timeouts, leaks
4. **Production-realistic**: Same pool used in real-world applications
5. **Metrics**: Built-in monitoring via Actuator

### Negative

1. **Client-side only**: Cannot demonstrate server-side pooling (PgBouncer)
2. **Connection limits**: Still bound by PostgreSQL `max_connections`

### Mitigations

- **Documentation**: Explain when to use PgBouncer in runbooks
- **Configuration**: Tune pool size based on workload: `pool_size = (core_count * 2) + effective_spindle_count`
- **Monitoring**: Expose HikariCP metrics via Prometheus

## Demonstration Scenarios

### 1. Connection Pool Exhaustion

```java
// Simulate connection leak
@GetMapping("/demos/leak")
public String demonstrateConnectionLeak() {
    Connection conn = dataSource.getConnection();
    // Intentionally don't close connection
    return "Connection leaked!";
}
```

**Result**: After 10 requests (pool size), subsequent requests timeout.

### 2. Connection Acquisition Time

```java
// Measure connection acquisition time
Timer.Sample sample = Timer.start(meterRegistry);
Connection conn = dataSource.getConnection();
sample.stop(Timer.builder("hikari.connection.acquire")
    .register(meterRegistry));
```

**Metrics**: Track `hikari.connection.acquire` to show <1ms acquisition time.

### 3. Leak Detection

Enable leak detection:
```yaml
spring.datasource.hikari.leak-detection-threshold: 5000
```

**Result**: Logs stack trace after 5 seconds if connection not returned.

## Monitoring and Metrics

HikariCP exposes these metrics via Micrometer:

```promql
# Active connections
hikaricp_connections_active

# Idle connections
hikaricp_connections_idle

# Pending threads (waiting for connection)
hikaricp_connections_pending

# Connection acquisition time
hikaricp_connections_acquire_seconds

# Connection usage time
hikaricp_connections_usage_seconds

# Connection creation time
hikaricp_connections_creation_seconds
```

**Grafana Dashboard**: Pre-built dashboards available for visualizing pool health.

## Best Practices

1. **Pool sizing**: `pool_size = (core_count * 2) + effective_spindle_count`
   - For OLTP with SSD: `pool_size = (cores * 2) + 1`
   - Example (4 cores): `maximum-pool-size: 9`

2. **Connection timeout**: Set based on acceptable latency
   - Interactive workloads: `connection-timeout: 5000` (5s)
   - Batch workloads: `connection-timeout: 30000` (30s)

3. **Max lifetime**: Prevent stale connections
   - `max-lifetime: 1800000` (30 minutes)
   - Must be less than PostgreSQL `idle_in_transaction_session_timeout`

4. **Leak detection**: Enable in development, disable in production
   - Development: `leak-detection-threshold: 10000` (10s)
   - Production: `leak-detection-threshold: 0` (disabled, overhead)

## References

- [HikariCP GitHub](https://github.com/brettwooldridge/HikariCP)
- [HikariCP: Down the Rabbit Hole](https://github.com/brettwooldridge/HikariCP/wiki/Down-the-Rabbit-Hole)
- [About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- [Spring Boot HikariCP Configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html#application-properties.data.spring.datasource.hikari)

## Review

- **Decision date**: 2025-11-16
- **Participants**: Technical team
- **Next review**: After connection pool tuning (Q1 2026)
