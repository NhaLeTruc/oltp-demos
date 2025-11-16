# Failure Handling and Resilience Patterns

**Demonstration**: Retry logic, circuit breakers, and connection pool exhaustion handling

**User Story**: US5 - Failure Scenarios and Recovery (T196)

**Functional Requirements**: FR-020, FR-021, FR-023

## Overview

This demonstration shows how to implement resilience patterns for handling failures gracefully in OLTP systems. The patterns demonstrated include retry with exponential backoff, circuit breaker for fault isolation, and connection pool exhaustion handling.

### What You'll Learn

- Retry logic with exponential backoff using Spring Retry
- Circuit breaker pattern with Resilience4j
- Connection pool exhaustion detection and handling
- Metrics and monitoring for resilience patterns
- Chaos engineering for failure testing

### Prerequisites

- Application running on `localhost:8080`
- PostgreSQL database running
- Basic understanding of resilience patterns
- (Optional) Chaos engineering tools (tc, toxiproxy)

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│ Client Request                                           │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
      ┌──────────────────────────────┐
      │ FailureDemoController        │
      │ - Retry endpoints            │
      │ - Circuit breaker endpoints  │
      │ - Recovery endpoints         │
      └──────────────┬───────────────┘
                     │
       ┌─────────────┴──────────────┐
       │                            │
       ▼                            ▼
┌─────────────────┐    ┌─────────────────────┐
│  RetryService   │    │ CircuitBreakerSvc   │
│                 │    │                     │
│ @Retryable      │    │ Resilience4j        │
│ @Recover        │    │ State Machine       │
│ Exponential     │    │ CLOSED/OPEN/        │
│ Backoff         │    │ HALF_OPEN           │
└─────────┬───────┘    └──────────┬──────────┘
          │                       │
          └───────────┬───────────┘
                      │
                      ▼
           ┌──────────────────────┐
           │  DatabaseConfig      │
           │                      │
           │  Pool Monitoring     │
           │  Exhaustion Detection│
           │  Alerting            │
           └──────────┬───────────┘
                      │
                      ▼
              ┌───────────────┐
              │  PostgreSQL   │
              │  Database     │
              └───────────────┘
```

## Retry Logic with Exponential Backoff

### Overview

Retry logic automatically re-attempts failed operations, useful for transient failures like network blips, temporary database unavailability, or connection timeouts.

**Exponential backoff** increases the delay between retries to avoid overwhelming the failing service:
- Attempt 1: Immediate
- Attempt 2: 100ms delay
- Attempt 3: 200ms delay
- Attempt 4: 400ms delay
- Attempt 5: 800ms delay

### Demonstration

```bash
# Transfer with retry (simulates transient failures)
curl -X POST http://localhost:8080/api/demos/failure/retry \
  -H 'Content-Type: application/json' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00,
    "simulateFailure": true
  }'
```

**Response (successful after retries)**:
```json
{
  "success": true,
  "attempts": 3,
  "transactionId": 42,
  "duration": "PT0.537S",
  "fallbackUsed": false,
  "correlationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

**Key Fields**:
- `attempts`: Number of retry attempts made (1 = success on first try, >1 = retries occurred)
- `duration`: Total time including retries
- `fallbackUsed`: Whether fallback logic was triggered (true = all retries failed)

### Retry Configuration

**Spring Retry Annotation**:
```java
@Retryable(
    retryFor = {TransientDataAccessException.class, QueryTimeoutException.class},
    maxAttempts = 5,
    backoff = @Backoff(
        delay = 100,        // Initial delay: 100ms
        multiplier = 2.0,   // Exponential backoff (2x each retry)
        maxDelay = 5000     // Cap at 5 seconds
    )
)
```

**Retryable Exceptions**:
- `TransientDataAccessException`: Temporary database errors
- `QueryTimeoutException`: Query execution timeout
- `SQLException`: Database connection errors

### Fallback Logic

When all retry attempts fail, the `@Recover` method provides graceful degradation:

```java
@Recover
public RetryResult recoverFromTransferFailure(
    Exception e,
    Long fromAccountId,
    Long toAccountId,
    BigDecimal amount,
    boolean simulateFailure
) {
    log.error("Transfer failed after all retries: error={}", e.getMessage());

    // Return graceful failure response
    return RetryResult.builder()
        .success(false)
        .fallbackUsed(true)
        .errorMessage(e.getMessage())
        .build();
}
```

### Retry Metrics

**Get retry metrics**:
```bash
curl http://localhost:8080/api/demos/failure/retry/metrics
```

**Response**:
```json
{
  "totalAttempts": 15,
  "totalSuccesses": 4,
  "totalFailures": 1,
  "totalFallbacks": 1,
  "successRate": "80.00%",
  "averageAttemptsPerOperation": 3.0
}
```

**Prometheus Metrics**:
```promql
# Total retry attempts
retry.attempts{type="transfer",reason="simulated_failure"}

# Successful retries
retry.success{type="transfer",attempts="3"}

# Fallback usage
retry.fallback{type="transfer",reason="TransientDataAccessException"}

# Retry duration
retry.duration{type="transfer",status="success"}
```

### Connection Test with Retry

Test database connectivity with automatic retry:

```bash
curl http://localhost:8080/api/demos/failure/retry/connection-test
```

**Use cases**:
- Health checks
- Database availability verification
- Connection pool warm-up

### Best Practices

1. **Only retry transient failures**
   - ✅ Network timeouts, temporary unavailability
   - ❌ Validation errors, business logic failures

2. **Set reasonable max attempts**
   - Too few: Gives up too quickly on transient issues
   - Too many: Delays error reporting, wastes resources
   - **Recommended**: 3-5 attempts

3. **Use exponential backoff**
   - Prevents thundering herd problem
   - Gives failing service time to recover
   - Reduces load on struggling systems

4. **Implement circuit breakers with retries**
   - Retries handle transient failures
   - Circuit breakers handle sustained failures
   - Use both for comprehensive resilience

5. **Monitor retry metrics**
   - High retry rate → investigate root cause
   - Frequent fallback → system capacity issue
   - Track p95/p99 latency including retries

## Circuit Breaker Pattern

### Overview

Circuit breaker prevents cascading failures by temporarily blocking calls to a failing service, giving it time to recover.

**States**:
1. **CLOSED**: Normal operation, requests flow through
2. **OPEN**: Fail fast, block all requests (service recovering)
3. **HALF_OPEN**: Test if service recovered (limited requests)

**State Transitions**:
```
CLOSED ──[failure rate > 50%]──> OPEN
  ▲                                │
  │                                │
  │                           [wait 10s]
  │                                │
  │                                ▼
  └──[success rate OK]──── HALF_OPEN
       ┌───────────────────────┘
       │
  [failures continue]
       │
       ▼
      OPEN
```

### Configuration

```java
CircuitBreakerConfig.custom()
    .failureRateThreshold(50)              // Open if 50% of requests fail
    .slowCallRateThreshold(50)             // Open if 50% of requests are slow
    .slowCallDurationThreshold(2s)         // Define "slow" as > 2 seconds
    .minimumNumberOfCalls(5)               // Need 5 calls before calculating rate
    .waitDurationInOpenState(10s)          // Wait 10s before trying HALF_OPEN
    .permittedNumberOfCallsInHalfOpenState(3)  // Allow 3 test calls
    .slidingWindowSize(10)                 // Track last 10 calls
```

### Demonstration

#### Normal Operation (CLOSED State)

```bash
# Successful transfer (circuit remains CLOSED)
curl -X POST http://localhost:8080/api/demos/failure/circuit-breaker \
  -H 'Content-Type: application/json' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 50.00,
    "simulateFailure": false
  }'
```

**Response**:
```json
{
  "success": true,
  "transactionId": 43,
  "stateBefore": "CLOSED",
  "stateAfter": "CLOSED",
  "duration": "PT0.025S",
  "rejected": false
}
```

#### Triggering Circuit Opening (CLOSED → OPEN)

```bash
# Trigger failures to open circuit
for i in {1..10}; do
  curl -X POST http://localhost:8080/api/demos/failure/circuit-breaker \
    -H 'Content-Type: application/json' \
    -d '{
      "fromAccountId": 1,
      "toAccountId": 2,
      "amount": 10.00,
      "simulateFailure": true
    }'
  echo ""
done
```

**Observation**: After 5+ failures, circuit transitions to OPEN

#### Rejected Calls (OPEN State)

When circuit is OPEN, requests are immediately rejected:

**Response (rejected)**:
```json
{
  "success": false,
  "transactionId": null,
  "stateBefore": "OPEN",
  "stateAfter": "OPEN",
  "duration": "PT0.001S",
  "rejected": true,
  "errorMessage": "Circuit breaker is OPEN - call rejected for fast fail"
}
```

**Benefits**:
- Fast failure (1ms vs waiting for timeout)
- Prevents resource exhaustion
- Gives failing service time to recover
- Reduces load on struggling system

### Circuit Breaker Metrics

**Get current state and metrics**:
```bash
curl http://localhost:8080/api/demos/failure/circuit-breaker/metrics
```

**Response**:
```json
{
  "state": "OPEN",
  "failureRate": "80.00%",
  "slowCallRate": "10.00%",
  "numberOfSuccessfulCalls": 2,
  "numberOfFailedCalls": 8,
  "numberOfSlowCalls": 1,
  "numberOfNotPermittedCalls": 5,
  "numberOfBufferedCalls": 10
}
```

**Prometheus Metrics**:
```promql
# Circuit breaker state transitions
circuit.breaker.state.transition{from="CLOSED",to="OPEN"}

# Call results
circuit.breaker.calls{type="transfer",result="success"}
circuit.breaker.calls{type="transfer",result="failure"}
circuit.breaker.calls{type="transfer",result="rejected"}
```

### Manual State Control

For testing and operational purposes:

```bash
# Manually open circuit
curl -X POST 'http://localhost:8080/api/demos/failure/circuit-breaker/transition?state=OPEN'

# Manually close circuit
curl -X POST 'http://localhost:8080/api/demos/failure/circuit-breaker/transition?state=CLOSED'

# Reset circuit to initial state
curl -X POST http://localhost:8080/api/demos/failure/circuit-breaker/reset
```

### Best Practices

1. **Configure appropriate thresholds**
   - Failure rate: 50-70% for most applications
   - Minimum calls: 5-10 to avoid false positives
   - Wait duration: 10-30s based on recovery time

2. **Monitor state transitions**
   - Alert on CLOSED → OPEN transitions
   - Log all state changes
   - Track time spent in OPEN state

3. **Combine with retries**
   - Retry handles transient failures
   - Circuit breaker handles sustained failures
   - Use both for layered resilience

4. **Set realistic slow call thresholds**
   - Based on p95/p99 latency from production
   - Account for acceptable variation
   - Don't trigger on minor slowdowns

5. **Test circuit breaker behavior**
   - Use chaos engineering to trigger failures
   - Verify HALF_OPEN state recovery
   - Ensure rejected calls handled gracefully

## Connection Pool Exhaustion

### Overview

Connection pool exhaustion occurs when all available database connections are in use, causing new requests to wait or fail. Proper handling prevents cascading failures.

**Detection Criteria**:
- CRITICAL: Pending threads > 5
- WARNING: Pool utilization > 80%
- WARNING: Connection wait time p95 > 100ms

### Monitoring

Connection pool health is monitored automatically every 5 seconds:

**Prometheus Metrics**:
```promql
# Pool state
oltp.connection.pool.active        # Active connections
oltp.connection.pool.idle          # Idle connections
oltp.connection.pool.total         # Total connections
oltp.connection.pool.pending       # Threads waiting for connections
oltp.connection.pool.utilization   # Utilization percentage

# Exhaustion events
oltp.connection.pool.exhaustion{severity="warning"}
oltp.connection.pool.exhaustion{severity="critical"}

# High utilization warnings
oltp.connection.pool.high_utilization{severity="warning"}  # >80%
oltp.connection.pool.high_utilization{severity="critical"} # >90%
```

### Application Logs

**WARNING (>80% utilization)**:
```
WARNING: Connection pool utilization high: 85.0% (17/20 connections active)
```

**CRITICAL (pending threads)**:
```
CRITICAL: Connection pool exhausted! 8 threads waiting for connections.
Consider increasing pool size or optimizing connection usage.
```

### Chaos Testing

**Simulate pool exhaustion**:
```bash
# Open 30 concurrent connections (pool max = 20)
./infrastructure/scripts/chaos/connection-exhaust.sh \
  --connections 30 \
  --duration 60
```

**Observation**:
1. First 20 connections succeed (pool capacity)
2. Next 10 connections wait (pending threads)
3. Application logs warnings
4. Metrics show exhaustion
5. After 60s, connections released and pool recovers

**During exhaustion**:
```bash
# Test retry behavior
curl -X POST http://localhost:8080/api/demos/failure/retry \
  -H 'Content-Type: application/json' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00,
    "simulateFailure": false
  }'
```

**Expected**: Request may retry multiple times waiting for connection

### Graceful Handling

**Application behavior under exhaustion**:

1. **Queue requests**: Wait up to `connectionTimeout` (250ms)
2. **Reject if critical**: If pending threads > 10, return 503 Service Unavailable
3. **Log warnings**: Alert operators to take action
4. **Expose metrics**: For monitoring and alerting

**Custom rejection logic** (in DatabaseConfig):
```java
public static void handlePoolExhaustion(
    HikariDataSource dataSource,
    int maxPendingThreads
) throws PoolExhaustedException {
    int pending = dataSource.getHikariPoolMXBean()
        .getThreadsAwaitingConnection();

    if (pending >= maxPendingThreads) {
        throw new PoolExhaustedException(
            "Connection pool exhausted: " + pending + " threads waiting"
        );
    }
}
```

### Best Practices

1. **Right-size connection pool**
   - Formula: `connections = ((core_count * 2) + effective_spindle_count)`
   - For OLTP: 10-20 connections typically sufficient
   - Monitor actual usage, don't over-provision

2. **Set appropriate timeouts**
   - Connection timeout: 250ms (fail fast)
   - Idle timeout: 10 minutes
   - Max lifetime: 30 minutes (prevent stale connections)

3. **Monitor pool metrics**
   - Alert on pending threads > 0
   - Alert on utilization > 80%
   - Track p95 connection wait time

4. **Optimize connection usage**
   - Use connection pooling (HikariCP)
   - Close connections promptly (try-with-resources)
   - Avoid long-running transactions
   - Consider read replicas for read-heavy workloads

5. **Handle exhaustion gracefully**
   - Return 503 Service Unavailable
   - Implement retry logic in clients
   - Use circuit breakers to prevent cascading failures
   - Scale horizontally if consistently exhausted

## Chaos Engineering

### Database Crash Simulation

```bash
# Simulate database crash
./infrastructure/scripts/chaos/kill-db.sh

# Hard kill (simulates power loss)
./infrastructure/scripts/chaos/kill-db.sh --hard-kill

# Stop without restart (manual recovery)
./infrastructure/scripts/chaos/kill-db.sh --no-restart
```

**Verifications**:
- ✅ Committed transactions survive crash
- ✅ Uncommitted transactions rolled back
- ✅ WAL replay completes successfully
- ✅ Application reconnects automatically

### Network Latency Injection

```bash
# Add 200ms latency
./infrastructure/scripts/chaos/network-latency.sh --latency 200ms

# Add latency with jitter (500ms ± 100ms)
./infrastructure/scripts/chaos/network-latency.sh \
  --latency 500ms \
  --jitter 100ms

# Add packet loss
./infrastructure/scripts/chaos/network-latency.sh --packet-loss 10

# Restore normal network
./infrastructure/scripts/chaos/network-latency.sh --restore
```

**Tests**:
- Retry logic with exponential backoff
- Circuit breaker triggering on slow calls
- Connection timeout handling
- Connection pool wait time metrics

### Connection Pool Exhaustion

```bash
# Exhaust pool with 50 concurrent connections
./infrastructure/scripts/chaos/connection-exhaust.sh \
  --connections 50 \
  --duration 120

# Release connections early
./infrastructure/scripts/chaos/connection-exhaust.sh --release
```

**Tests**:
- Pool exhaustion detection
- Graceful queueing
- Request rejection at critical threshold
- Pool recovery after load decrease

## Summary

This demonstration shows how to:

✅ Implement retry logic with exponential backoff
✅ Configure circuit breakers for fault isolation
✅ Detect and handle connection pool exhaustion
✅ Monitor resilience patterns with metrics
✅ Test failures with chaos engineering
✅ Gracefully degrade under sustained failures
✅ Prevent cascading failures across services

For more demonstrations:
- [Crash Recovery and Durability](./recovery.md)
- [Distributed Tracing](../observability/tracing.md)
- [Metrics and Monitoring](../observability/metrics.md)

## References

- [Spring Retry Documentation](https://github.com/spring-projects/spring-retry)
- [Resilience4j Circuit Breaker](https://resilience4j.readme.io/docs/circuitbreaker)
- [HikariCP Configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)
- [Chaos Engineering Principles](https://principlesofchaos.org/)
