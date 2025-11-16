# Deadlock Detection and Prevention

**Demonstration**: Deadlock scenarios, detection, and prevention strategies

**User Story**: US2 - Concurrency and Conflict Handling (T108)

**Functional Requirements**: FR-011

## Overview

A deadlock occurs when two or more transactions are waiting for each other to release locks, creating a circular dependency where none can proceed. PostgreSQL automatically detects deadlocks and aborts one transaction to break the cycle.

### What You'll Learn

- What deadlocks are and how they occur
- PostgreSQL deadlock detection mechanism
- Deadlock prevention strategies
- Lock ordering techniques
- Deadlock monitoring and metrics
- Application-level deadlock handling

### Prerequisites

- Application running on `localhost:8080`
- PostgreSQL database initialized
- Understanding of pessimistic locking
- Multiple terminal windows for parallel requests

## Deadlock Concept

```
┌────────────────────────────────────────────────────┐
│ Classic Deadlock: Circular Wait                   │
│                                                     │
│ Transaction A              Transaction B           │
│ ─────────────              ─────────────           │
│                                                     │
│ BEGIN;                     BEGIN;                  │
│ SELECT * FROM accounts     SELECT * FROM accounts  │
│   WHERE id = 1               WHERE id = 2          │
│   FOR UPDATE;                FOR UPDATE;           │
│ ✓ Lock on account 1        ✓ Lock on account 2    │
│                                                     │
│ SELECT * FROM accounts     SELECT * FROM accounts  │
│   WHERE id = 2               WHERE id = 1          │
│   FOR UPDATE;                FOR UPDATE;           │
│ ⏳ Waiting for lock on 2   ⏳ Waiting for lock on 1│
│                                                     │
│ Deadlock! Each waits for the other's lock         │
│                                                     │
│ PostgreSQL detects cycle after deadlock_timeout   │
│ (default: 1 second)                                │
│                                                     │
│ ✗ ABORTED (victim)         ✓ Proceeds              │
│ DeadlockException          Lock acquired           │
│                            COMMIT;                 │
└────────────────────────────────────────────────────┘

Deadlock Graph:
   Transaction A ──► Lock on 1
        ▲               │
        │               │
    Waits for       Holds lock
        │               │
        │               ▼
   Lock on 2 ◄── Transaction B
        ▲               │
        │               │
    Holds lock      Waits for
        │               │
        └───────────────┘
           Cycle = Deadlock!
```

## PostgreSQL Deadlock Detection

### How PostgreSQL Detects Deadlocks

1. **Wait Graph**: PostgreSQL maintains a wait-for graph
2. **Cycle Detection**: Periodically checks for cycles in the graph
3. **Timeout**: Checks after `deadlock_timeout` (default: 1 second)
4. **Victim Selection**: Aborts transaction with lowest cost
5. **Error**: Victim receives `40P01` SQL state (deadlock_detected)

### Deadlock Configuration

```sql
-- View current deadlock timeout
SHOW deadlock_timeout;
-- Default: 1s

-- Adjust deadlock timeout (session-level)
SET deadlock_timeout = '2s';

-- Adjust deadlock timeout (database-level)
ALTER DATABASE mydb SET deadlock_timeout = '500ms';
```

**Trade-off**:
- **Short timeout** (100ms-500ms): Fast detection, but more false positives
- **Long timeout** (2s-5s): Fewer false positives, but slower detection

## Demonstrations

### 1. Simulate Classic Deadlock

**Scenario**: Two transfers in opposite directions deadlock

**Terminal 1**:
```bash
# Transfer: Account 1 → Account 2
curl -X POST http://localhost:8080/api/demos/concurrency/deadlock/create-deadlock \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: deadlock-demo-001' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00,
    "delayBetweenLocks": 2000
  }'
```

**Terminal 2** (execute within 2 seconds):
```bash
# Transfer: Account 2 → Account 1 (opposite direction)
curl -X POST http://localhost:8080/api/demos/concurrency/deadlock/create-deadlock \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: deadlock-demo-002' \
  -d '{
    "fromAccountId": 2,
    "toAccountId": 1,
    "amount": 50.00,
    "delayBetweenLocks": 2000
  }'
```

**Terminal 1 Response** (deadlock victim):
```json
{
  "success": false,
  "correlationId": "deadlock-demo-001",
  "error": "DeadlockDetectedException",
  "sqlState": "40P01",
  "message": "Deadlock detected while waiting for lock",
  "detail": "Process 12345 waits for ExclusiveLock on tuple of relation accounts; blocked by process 12346",
  "retryable": true,
  "recommendation": "Retry transaction (deadlock victim was chosen for abort)",
  "deadlockGraph": {
    "waitingFor": "account_id=2",
    "holding": "account_id=1",
    "blockedBy": "deadlock-demo-002"
  }
}
```

**Terminal 2 Response** (survivor):
```json
{
  "success": true,
  "correlationId": "deadlock-demo-002",
  "transactionId": 701,
  "message": "Transfer completed successfully after deadlock resolution",
  "note": "Other transaction was aborted (deadlock victim)"
}
```

**Application Logs**:
```
2025-11-16 10:25:15.123 [deadlock-demo-001] INFO  DeadlockService - Acquiring lock: account=1
2025-11-16 10:25:15.125 [deadlock-demo-002] INFO  DeadlockService - Acquiring lock: account=2
2025-11-16 10:25:17.130 [deadlock-demo-001] INFO  DeadlockService - Acquiring lock: account=2 (waiting...)
2025-11-16 10:25:17.132 [deadlock-demo-002] INFO  DeadlockService - Acquiring lock: account=1 (waiting...)
2025-11-16 10:25:18.135 [deadlock-demo-001] ERROR DeadlockService - Deadlock detected: SqlState=40P01
2025-11-16 10:25:18.136 [deadlock-demo-001] INFO  DeadlockService - Transaction aborted (deadlock victim)
2025-11-16 10:25:18.137 [deadlock-demo-002] INFO  DeadlockService - Lock acquired on account=1
2025-11-16 10:25:18.189 [deadlock-demo-002] INFO  DeadlockService - Transfer completed successfully
```

**Key Points**:
- ✅ PostgreSQL detected circular wait after ~1 second
- ✅ Aborted one transaction (victim)
- ✅ Other transaction proceeded successfully
- ✅ Application received specific error code (40P01)
- ✅ Retrying victim transaction would likely succeed

### 2. Prevent Deadlock with Lock Ordering

**Scenario**: Same transfers with consistent lock ordering (no deadlock)

**Terminal 1**:
```bash
# Transfer: Account 1 → Account 2 (with ordering)
curl -X POST http://localhost:8080/api/demos/concurrency/deadlock/transfer-with-ordering \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: deadlock-demo-003' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00
  }'
```

**Terminal 2** (execute simultaneously):
```bash
# Transfer: Account 2 → Account 1 (with ordering)
curl -X POST http://localhost:8080/api/demos/concurrency/deadlock/transfer-with-ordering \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: deadlock-demo-004' \
  -d '{
    "fromAccountId": 2,
    "toAccountId": 1,
    "amount": 50.00
  }'
```

**Both succeed** (sequential execution, no deadlock):
```json
{
  "success": true,
  "correlationId": "deadlock-demo-003",
  "transactionId": 702,
  "lockOrder": [1, 2],
  "message": "Transfer completed with ordered lock acquisition (no deadlock)"
}
```

**Application Logs**:
```
2025-11-16 10:30:15.123 [deadlock-demo-003] INFO  DeadlockService - Lock order: [1, 2]
2025-11-16 10:30:15.125 [deadlock-demo-004] INFO  DeadlockService - Lock order: [1, 2] (same order)
2025-11-16 10:30:15.130 [deadlock-demo-003] INFO  DeadlockService - Acquired lock: account=1
2025-11-16 10:30:15.135 [deadlock-demo-004] INFO  DeadlockService - Waiting for lock: account=1
2025-11-16 10:30:15.140 [deadlock-demo-003] INFO  DeadlockService - Acquired lock: account=2
2025-11-16 10:30:15.189 [deadlock-demo-003] INFO  DeadlockService - Transfer completed, locks released
2025-11-16 10:30:15.190 [deadlock-demo-004] INFO  DeadlockService - Acquired lock: account=1
2025-11-16 10:30:15.195 [deadlock-demo-004] INFO  DeadlockService - Acquired lock: account=2
2025-11-16 10:30:15.240 [deadlock-demo-004] INFO  DeadlockService - Transfer completed
```

**Key Points**:
- ✅ Both transactions lock in same order (1, 2)
- ✅ No circular wait possible
- ✅ Sequential execution (one waits, then proceeds)
- ✅ No deadlock, both succeed

### 3. Automatic Retry on Deadlock

**Scenario**: Application automatically retries deadlock victims

```bash
curl -X POST http://localhost:8080/api/demos/concurrency/deadlock/transfer-with-retry \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: deadlock-demo-005' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00,
    "maxRetries": 3
  }'
```

**Response** (successful after retry):
```json
{
  "success": true,
  "correlationId": "deadlock-demo-005",
  "transactionId": 703,
  "attempts": 2,
  "retriesNeeded": 1,
  "deadlocksEncountered": 1,
  "message": "Transfer completed successfully after 1 deadlock retry"
}
```

**Application Logs**:
```
2025-11-16 10:35:15.123 [deadlock-demo-005] INFO  DeadlockService - Starting transfer (attempt 1/3)
2025-11-16 10:35:16.135 [deadlock-demo-005] ERROR DeadlockService - Deadlock detected on attempt 1
2025-11-16 10:35:16.136 [deadlock-demo-005] WARN  DeadlockService - Retrying after deadlock (attempt 2/3)
2025-11-16 10:35:16.500 [deadlock-demo-005] INFO  DeadlockService - Transfer completed on attempt 2
```

## Deadlock Prevention Strategies

### 1. Consistent Lock Ordering

**Problem**: Inconsistent lock acquisition order causes deadlocks

```java
// ❌ Bad: Inconsistent order (deadlock risk)
@Transactional
public void badTransfer(Long fromId, Long toId, BigDecimal amount) {
    Account from = accountRepository.findByIdWithExclusiveLock(fromId).orElseThrow();
    Account to = accountRepository.findByIdWithExclusiveLock(toId).orElseThrow();
    // Transaction A locks (1, 2), Transaction B locks (2, 1) → Deadlock!
}
```

**Solution**: Always acquire locks in same order

```java
// ✅ Good: Consistent order (no deadlock)
@Transactional
public void goodTransfer(Long fromId, Long toId, BigDecimal amount) {
    // Always lock lower ID first
    Long firstId = Math.min(fromId, toId);
    Long secondId = Math.max(fromId, toId);

    Account first = accountRepository.findByIdWithExclusiveLock(firstId).orElseThrow();
    Account second = accountRepository.findByIdWithExclusiveLock(secondId).orElseThrow();

    // Determine actual from/to after locking
    Account from = fromId.equals(firstId) ? first : second;
    Account to = toId.equals(firstId) ? first : second;

    // Execute transfer
    from.debit(amount);
    to.credit(amount);

    accountRepository.save(from);
    accountRepository.save(to);
}
```

### 2. Acquire All Locks at Once

**Problem**: Incremental lock acquisition can deadlock

```java
// ❌ Bad: Incremental locking
@Transactional
public void badMultiAccountTransfer(List<Long> accountIds) {
    for (Long id : accountIds) {
        Account account = accountRepository.findByIdWithExclusiveLock(id).orElseThrow();
        // Process...
    }
    // Different transaction order → Deadlock risk
}
```

**Solution**: Lock all resources upfront

```java
// ✅ Good: Lock all at once
@Transactional
public void goodMultiAccountTransfer(List<Long> accountIds) {
    // Sort IDs to ensure consistent order
    List<Long> sortedIds = accountIds.stream()
        .sorted()
        .collect(Collectors.toList());

    // Acquire all locks upfront in sorted order
    List<Account> accounts = sortedIds.stream()
        .map(id -> accountRepository.findByIdWithExclusiveLock(id).orElseThrow())
        .collect(Collectors.toList());

    // Process all accounts (locks already held)
    accounts.forEach(this::process);
}
```

### 3. Use Lock Timeout

**Problem**: Deadlock detection takes time (default: 1 second)

```java
// ❌ Bad: No timeout, waits full deadlock_timeout
@Transactional
public void noTimeout(Long id) {
    Account account = accountRepository.findByIdWithExclusiveLock(id).orElseThrow();
    // May wait 1+ second if deadlock
}
```

**Solution**: Set explicit timeout to fail fast

```java
// ✅ Good: Explicit timeout
@Transactional
public void withTimeout(Long id) {
    // Fail after 500ms instead of waiting for deadlock detection
    entityManager.createNativeQuery("SET LOCAL lock_timeout = '500ms'")
        .executeUpdate();

    try {
        Account account = accountRepository.findByIdWithExclusiveLock(id).orElseThrow();
        // Process...
    } catch (LockTimeoutException e) {
        // Timeout occurred (possible deadlock or contention)
        throw new RetryableException("Lock timeout, retry recommended", e);
    }
}
```

### 4. Minimize Lock Duration

**Problem**: Long lock duration increases deadlock probability

```java
// ❌ Bad: Long transaction holding locks
@Transactional
public void longTransaction(Long id) {
    Account account = accountRepository.findByIdWithExclusiveLock(id).orElseThrow();

    // External API call while holding lock
    externalService.call();  // 500ms+

    account.setBalance(newBalance);
    accountRepository.save(account);
}
```

**Solution**: Acquire locks only when needed

```java
// ✅ Good: Short critical section
@Transactional
public void shortTransaction(Long id, BigDecimal newBalance) {
    // Do external work outside transaction
    ValidationResult validation = externalService.validate();

    // Lock only during actual update
    Account account = accountRepository.findByIdWithExclusiveLock(id).orElseThrow();
    account.setBalance(newBalance);
    accountRepository.save(account);
}  // Lock released quickly
```

### 5. Use Optimistic Locking When Appropriate

**Problem**: Pessimistic locks can deadlock, optimistic locks never deadlock

```java
// ❌ Pessimistic locking (deadlock risk)
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Transactional
public void pessimisticUpdate(Long id) {
    Account account = accountRepository.findById(id).orElseThrow();
    account.setBalance(newBalance);
}

// ✅ Optimistic locking (no deadlock, but may need retry)
@Transactional
public void optimisticUpdate(Long id) {
    Account account = accountRepository.findById(id).orElseThrow();
    account.setBalance(newBalance);
    accountRepository.save(account);  // May throw OptimisticLockException
}
```

## Implementation Details

### Deadlock Retry Logic

```java
@Service
public class DeadlockRetryService {

    private static final int MAX_RETRIES = 3;

    @Retryable(
        value = {DeadlockDetectedException.class, PSQLException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2, random = true)
    )
    @Transactional
    public TransferResult transferWithRetry(
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount
    ) {
        try {
            return executeTransfer(fromAccountId, toAccountId, amount);
        } catch (PSQLException e) {
            // Check if it's a deadlock (SQL state: 40P01)
            if ("40P01".equals(e.getSQLState())) {
                log.warn("Deadlock detected, will retry");
                throw new DeadlockDetectedException("Deadlock occurred", e);
            }
            throw e;
        }
    }

    private TransferResult executeTransfer(
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount
    ) {
        // Consistent lock ordering
        Long firstId = Math.min(fromAccountId, toAccountId);
        Long secondId = Math.max(fromAccountId, toAccountId);

        Account first = accountRepository.findByIdWithExclusiveLock(firstId).orElseThrow();
        Account second = accountRepository.findByIdWithExclusiveLock(secondId).orElseThrow();

        // Execute transfer
        Account from = fromAccountId.equals(firstId) ? first : second;
        Account to = toAccountId.equals(firstId) ? first : second;

        from.debit(amount);
        to.credit(amount);

        accountRepository.save(from);
        accountRepository.save(to);

        return TransferResult.success(from, to, amount);
    }

    @Recover
    public TransferResult recover(
        DeadlockDetectedException e,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount
    ) {
        log.error("Failed after {} retries due to deadlocks", MAX_RETRIES);
        throw new MaxDeadlockRetriesExceededException(
            "Transfer failed after " + MAX_RETRIES + " deadlock retries"
        );
    }
}
```

### Manual Retry with Backoff

```java
@Service
public class ManualDeadlockRetryService {

    public TransferResult transferWithManualRetry(
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        int maxRetries
    ) {
        int attempt = 0;
        Random random = new Random();

        while (attempt < maxRetries) {
            try {
                return executeTransfer(fromAccountId, toAccountId, amount);
            } catch (PSQLException e) {
                if (!"40P01".equals(e.getSQLState())) {
                    throw e;  // Not a deadlock, re-throw
                }

                attempt++;
                if (attempt >= maxRetries) {
                    throw new MaxDeadlockRetriesExceededException(
                        "Failed after " + maxRetries + " deadlock retries", e
                    );
                }

                // Exponential backoff with jitter
                long baseDelay = (long) Math.pow(2, attempt) * 100;
                long jitter = random.nextInt(100);
                long delayMs = baseDelay + jitter;

                log.warn("Deadlock on attempt {}/{}, retrying after {}ms",
                    attempt, maxRetries, delayMs);

                Thread.sleep(delayMs);
            }
        }

        throw new IllegalStateException("Should not reach here");
    }

    @Transactional
    private TransferResult executeTransfer(...) {
        // Transfer with lock ordering
    }
}
```

### Exception Handling

```java
@RestControllerAdvice
public class DeadlockExceptionHandler {

    @ExceptionHandler(DeadlockDetectedException.class)
    public ResponseEntity<ErrorResponse> handleDeadlock(
        DeadlockDetectedException ex,
        HttpServletRequest request
    ) {
        String correlationId = request.getHeader("X-Correlation-ID");

        ErrorResponse error = ErrorResponse.builder()
            .error("DeadlockDetectedException")
            .sqlState("40P01")
            .message("Deadlock detected while waiting for lock")
            .retryable(true)
            .recommendation("Retry transaction (victim was chosen for abort)")
            .correlationId(correlationId)
            .timestamp(Instant.now())
            .build();

        log.error("[{}] Deadlock detected: {}", correlationId, ex.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }
}
```

## Deadlock Monitoring

### View Deadlock Information

```bash
curl http://localhost:8080/api/demos/concurrency/deadlock/info
```

**Response**:
```json
{
  "deadlockTimeout": "1s",
  "recentDeadlocks": [
    {
      "timestamp": "2025-11-16T10:25:18.135Z",
      "victim": {
        "pid": 12345,
        "query": "SELECT * FROM accounts WHERE id = 2 FOR UPDATE",
        "correlationId": "deadlock-demo-001"
      },
      "survivor": {
        "pid": 12346,
        "query": "SELECT * FROM accounts WHERE id = 1 FOR UPDATE",
        "correlationId": "deadlock-demo-002"
      },
      "waitGraph": {
        "12345": ["waiting_for_lock_on_account_2", "holding_lock_on_account_1"],
        "12346": ["waiting_for_lock_on_account_1", "holding_lock_on_account_2"]
      }
    }
  ],
  "deadlockCount24h": 5,
  "deadlockRate": 0.02
}
```

### PostgreSQL Deadlock Logs

**Enable deadlock logging**:
```sql
-- Log all deadlocks
ALTER SYSTEM SET log_lock_waits = on;
ALTER SYSTEM SET deadlock_timeout = '1s';
ALTER SYSTEM SET log_min_error_statement = 'error';

-- Reload configuration
SELECT pg_reload_conf();
```

**PostgreSQL logs** (when deadlock occurs):
```
2025-11-16 10:25:18.135 UTC [12345] ERROR:  deadlock detected
2025-11-16 10:25:18.135 UTC [12345] DETAIL:  Process 12345 waits for ExclusiveLock on tuple (0,1) of relation 16385 (accounts); blocked by process 12346.
    Process 12346 waits for ExclusiveLock on tuple (0,2) of relation 16385 (accounts); blocked by process 12345.
2025-11-16 10:25:18.135 UTC [12345] HINT:  See server log for query details.
2025-11-16 10:25:18.135 UTC [12345] CONTEXT:  while locking tuple (0,2) in relation "accounts"
2025-11-16 10:25:18.135 UTC [12345] STATEMENT:  SELECT * FROM accounts WHERE id = 2 FOR UPDATE
```

### Metrics

```promql
# Deadlock rate
rate(deadlocks_detected[5m])

# Deadlock retries
rate(deadlock_retries[5m])

# Deadlock success rate after retry
rate(deadlock_success_after_retry[5m]) /
rate(deadlocks_detected[5m])

# Most common deadlock pairs
topk(10, sum by (account_pair) (
  increase(deadlocks{entity="Account"}[1h])
))
```

### Application Metrics

```java
@Aspect
@Component
public class DeadlockMonitor {

    private final MeterRegistry meterRegistry;

    @Around("execution(* com.demo.service.*.*(..))")
    public Object monitorDeadlocks(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (PSQLException e) {
            if ("40P01".equals(e.getSQLState())) {
                meterRegistry.counter("deadlocks_detected",
                    "method", joinPoint.getSignature().getName()
                ).increment();
            }
            throw e;
        }
    }
}
```

## Best Practices

### 1. Always Use Consistent Lock Ordering

```java
// ✅ Good: Sort IDs before locking
List<Long> ids = Arrays.asList(fromId, toId);
Collections.sort(ids);
for (Long id : ids) {
    lock(id);
}

// ❌ Bad: Lock in arbitrary order
lock(fromId);
lock(toId);
```

### 2. Minimize Lock Hold Time

```java
// ✅ Good: Short critical section
@Transactional
public void quickUpdate() {
    Account account = lockAndLoad();
    account.update();
    save(account);
}  // Locks released

// ❌ Bad: Long-running transaction
@Transactional
public void slowUpdate() {
    Account account = lockAndLoad();
    externalApiCall();  // Holding lock during I/O
    account.update();
}
```

### 3. Implement Retry Logic with Backoff

```java
@Retryable(
    value = {DeadlockDetectedException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 100, multiplier = 2, random = true)
)
// Random jitter prevents thundering herd
```

### 4. Set Appropriate Lock Timeout

```java
// Detect deadlocks faster than default 1 second
entityManager.createNativeQuery("SET LOCAL lock_timeout = '500ms'")
    .executeUpdate();
```

### 5. Monitor and Alert on Deadlocks

```yaml
# Alert on high deadlock rate
- alert: HighDeadlockRate
  expr: rate(deadlocks_detected[5m]) > 0.1
  for: 5m
  annotations:
    summary: "High deadlock rate detected"
    description: "{{ $value }} deadlocks/second"
```

### 6. Log Deadlock Details

```java
catch (PSQLException e) {
    if ("40P01".equals(e.getSQLState())) {
        log.error("Deadlock detected: correlationId={}, query={}, detail={}",
            correlationId, query, e.getMessage());
    }
}
```

## Summary

This demonstration shows:

✅ What deadlocks are and how they occur
✅ PostgreSQL automatic deadlock detection
✅ Deadlock prevention through lock ordering
✅ Retry strategies for deadlock victims
✅ Deadlock monitoring and logging
✅ Best practices to minimize deadlock risk

**Key Takeaways**:
- Deadlocks occur when transactions wait for each other's locks (circular wait)
- PostgreSQL automatically detects and aborts one transaction
- Consistent lock ordering prevents most deadlocks
- Always implement retry logic for deadlock errors
- Monitor deadlock frequency to identify problematic code paths
- Minimize lock hold time to reduce deadlock probability

For more demonstrations:
- [Optimistic Locking](./optimistic-locking.md) - Version-based concurrency (never deadlocks)
- [Pessimistic Locking](./pessimistic-locking.md) - Lock-based concurrency
- [Isolation Levels](../acid/isolation.md) - Transaction isolation

## References

- [PostgreSQL Deadlocks](https://www.postgresql.org/docs/current/explicit-locking.html#LOCKING-DEADLOCKS)
- [Deadlock Detection](https://www.postgresql.org/docs/current/runtime-config-locks.html)
- [Lock Monitoring](https://www.postgresql.org/docs/current/view-pg-locks.html)
- [Deadlock Prevention](https://en.wikipedia.org/wiki/Deadlock_prevention_algorithms)
