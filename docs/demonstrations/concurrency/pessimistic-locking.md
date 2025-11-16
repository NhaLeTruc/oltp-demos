# Pessimistic Locking Demonstration

**Demonstration**: Lock-based concurrency control and conflict prevention

**User Story**: US2 - Concurrency and Conflict Handling (T107)

**Functional Requirements**: FR-010

## Overview

Pessimistic locking prevents conflicts by acquiring locks on database rows before modification. Unlike optimistic locking, which detects conflicts at commit time, pessimistic locking blocks competing transactions until the lock is released.

### What You'll Learn

- JPA @Lock annotation for pessimistic locking
- Lock types (PESSIMISTIC_READ, PESSIMISTIC_WRITE, PESSIMISTIC_FORCE_INCREMENT)
- SELECT FOR UPDATE and SELECT FOR SHARE
- Lock timeout configuration and handling
- Lock contention monitoring
- When to use pessimistic vs optimistic locking

### Prerequisites

- Application running on `localhost:8080`
- PostgreSQL database initialized
- Understanding of concurrent transactions
- Multiple terminal windows for parallel requests

## Pessimistic Locking Concept

```
┌────────────────────────────────────────────────────┐
│ Pessimistic Locking: "Lock it first, modify later"│
│                                                     │
│ Transaction A              Transaction B           │
│ ─────────────              ─────────────           │
│                                                     │
│ BEGIN;                     BEGIN;                  │
│ SELECT * FROM accounts     SELECT * FROM accounts  │
│   WHERE id = 1               WHERE id = 1          │
│   FOR UPDATE;                FOR UPDATE;           │
│ ✓ Lock acquired           ⏳ BLOCKED (waiting)    │
│                                                     │
│ UPDATE accounts            (still waiting...)      │
│   SET balance = 900                                │
│   WHERE id = 1;                                    │
│                                                     │
│ COMMIT;                    ✓ Lock acquired         │
│ (lock released)            UPDATE accounts         │
│                              SET balance = 800     │
│                              WHERE id = 1;         │
│                            COMMIT;                 │
│                            (lock released)         │
│                                                     │
│ Result: Sequential execution, no conflicts        │
└────────────────────────────────────────────────────┘

vs Optimistic Locking: "Hope for the best"
┌────────────────────────────────────────────────────┐
│ Transaction A              Transaction B           │
│ ─────────────              ─────────────           │
│                                                     │
│ SELECT (version=1)         SELECT (version=1)      │
│ ✓ No lock                  ✓ No lock               │
│ Modify in memory           Modify in memory        │
│ ✓ COMMIT (v1→v2)           ✗ FAIL (v already 2)   │
│                            OptimisticLockException │
│                            Must retry              │
└────────────────────────────────────────────────────┘
```

## Lock Types

### 1. PESSIMISTIC_WRITE (Exclusive Lock)

**SQL**: `SELECT ... FOR UPDATE`

**Behavior**:
- Blocks other transactions from reading with lock or updating
- Allows non-locking reads (SELECT without FOR UPDATE)
- Use when you intend to modify the row

**JPA**:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
Account findById(Long id);
```

**PostgreSQL**:
```sql
SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
-- Other transactions:
--   SELECT ... FOR UPDATE → BLOCKED
--   SELECT ... FOR SHARE  → BLOCKED
--   UPDATE ...            → BLOCKED
--   SELECT ...            → ALLOWED (no lock)
```

### 2. PESSIMISTIC_READ (Shared Lock)

**SQL**: `SELECT ... FOR SHARE`

**Behavior**:
- Allows other transactions to read with shared lock
- Blocks transactions from updating or acquiring exclusive lock
- Use when you want to prevent updates while reading

**JPA**:
```java
@Lock(LockModeType.PESSIMISTIC_READ)
Account findById(Long id);
```

**PostgreSQL**:
```sql
SELECT * FROM accounts WHERE id = 1 FOR SHARE;
-- Other transactions:
--   SELECT ... FOR SHARE  → ALLOWED (shared lock)
--   SELECT ... FOR UPDATE → BLOCKED
--   UPDATE ...            → BLOCKED
--   SELECT ...            → ALLOWED (no lock)
```

### 3. PESSIMISTIC_FORCE_INCREMENT

**Behavior**:
- Like PESSIMISTIC_WRITE but increments version field
- Combines pessimistic and optimistic locking
- Use when you want both lock protection and version tracking

**JPA**:
```java
@Lock(LockModeType.PESSIMISTIC_FORCE_INCREMENT)
Account findById(Long id);
```

## Demonstrations

### 1. Exclusive Lock (FOR UPDATE)

**Scenario**: Two transactions try to update same account

**Terminal 1**:
```bash
# Start transfer with delay (holds lock)
curl -X POST http://localhost:8080/api/demos/concurrency/pessimistic/transfer-with-lock \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: pessimistic-demo-001' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00,
    "lockType": "PESSIMISTIC_WRITE",
    "holdLockSeconds": 10
  }'
```

**Terminal 2** (execute within 10 seconds):
```bash
# Attempt concurrent transfer on same account
curl -X POST http://localhost:8080/api/demos/concurrency/pessimistic/transfer-with-lock \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: pessimistic-demo-002' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 3,
    "amount": 50.00,
    "lockType": "PESSIMISTIC_WRITE",
    "lockTimeout": 5000
  }'
```

**Terminal 1 Response** (completes after 10 seconds):
```json
{
  "success": true,
  "correlationId": "pessimistic-demo-001",
  "transactionId": 601,
  "lockType": "PESSIMISTIC_WRITE",
  "lockAcquired": true,
  "lockWaitTime": 0,
  "lockHoldTime": 10000,
  "message": "Transfer completed successfully with exclusive lock"
}
```

**Terminal 2 Response** (timeout after 5 seconds):
```json
{
  "success": false,
  "correlationId": "pessimistic-demo-002",
  "error": "LockTimeoutException",
  "message": "Could not acquire lock within timeout period",
  "lockTimeout": 5000,
  "blockedBy": "pessimistic-demo-001",
  "recommendation": "Retry later or increase timeout"
}
```

**Key Points**:
- ✅ Transaction A acquired lock first
- ✅ Transaction B blocked waiting for lock
- ✅ Transaction B timed out after 5 seconds
- ✅ No conflict - sequential execution enforced

### 2. Shared Lock (FOR SHARE)

**Scenario**: Multiple readers, one writer

**Terminal 1** (reader):
```bash
curl -X POST http://localhost:8080/api/demos/concurrency/pessimistic/read-with-lock \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: pessimistic-demo-003' \
  -d '{
    "accountId": 1,
    "lockType": "PESSIMISTIC_READ",
    "holdLockSeconds": 10
  }'
```

**Terminal 2** (another reader, execute immediately):
```bash
curl -X POST http://localhost:8080/api/demos/concurrency/pessimistic/read-with-lock \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: pessimistic-demo-004' \
  -d '{
    "accountId": 1,
    "lockType": "PESSIMISTIC_READ"
  }'
```

**Terminal 3** (writer, execute immediately):
```bash
curl -X POST http://localhost:8080/api/demos/concurrency/pessimistic/update-balance \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: pessimistic-demo-005' \
  -d '{
    "accountId": 1,
    "newBalance": 5000.00,
    "lockTimeout": 3000
  }'
```

**Terminal 1 & 2 Response** (both succeed):
```json
{
  "success": true,
  "correlationId": "pessimistic-demo-003",
  "lockType": "PESSIMISTIC_READ",
  "lockAcquired": true,
  "sharedLock": true,
  "message": "Read with shared lock (allows other readers)"
}
```

**Terminal 3 Response** (blocked, then timeout):
```json
{
  "success": false,
  "correlationId": "pessimistic-demo-005",
  "error": "LockTimeoutException",
  "message": "Could not acquire exclusive lock (blocked by shared locks)",
  "blockedBy": ["pessimistic-demo-003", "pessimistic-demo-004"]
}
```

**Key Points**:
- ✅ Multiple shared locks allowed (readers don't block readers)
- ✅ Exclusive lock blocked by shared locks (writer waits for readers)
- ✅ Prevents dirty reads and lost updates

### 3. Lock Timeout and Retry

**Scenario**: Handle lock timeout with automatic retry

```bash
curl -X POST http://localhost:8080/api/demos/concurrency/pessimistic/transfer-with-retry \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: pessimistic-demo-006' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00,
    "lockTimeout": 2000,
    "maxRetries": 3,
    "retryDelay": 1000
  }'
```

**Response** (after retry):
```json
{
  "success": true,
  "correlationId": "pessimistic-demo-006",
  "transactionId": 602,
  "attempts": 2,
  "retriesNeeded": 1,
  "totalWaitTime": 3000,
  "message": "Transfer completed after 1 retry due to lock timeout"
}
```

**Application Logs**:
```
2025-11-16 10:20:15.123 [pessimistic-demo-006] INFO  PessimisticService - Acquiring lock: account=1, timeout=2000ms
2025-11-16 10:20:17.125 [pessimistic-demo-006] WARN  PessimisticService - Lock timeout on attempt 1
2025-11-16 10:20:18.126 [pessimistic-demo-006] INFO  PessimisticService - Retrying (attempt 2/3)
2025-11-16 10:20:18.150 [pessimistic-demo-006] INFO  PessimisticService - Lock acquired on attempt 2
2025-11-16 10:20:18.189 [pessimistic-demo-006] INFO  PessimisticService - Transfer completed successfully
```

## Implementation Details

### JPA Repository with Pessimistic Lock

```java
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    // Exclusive lock (FOR UPDATE)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithExclusiveLock(@Param("id") Long id);

    // Shared lock (FOR SHARE)
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithSharedLock(@Param("id") Long id);

    // Force increment (FOR UPDATE + version bump)
    @Lock(LockModeType.PESSIMISTIC_FORCE_INCREMENT)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithForceIncrement(@Param("id") Long id);
}
```

### Service with Lock Timeout

```java
@Service
public class PessimisticTransferService {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public TransferResult transferWithLock(
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        int lockTimeoutMs
    ) {
        // Set lock timeout for this transaction
        entityManager.createNativeQuery(
            "SET LOCAL lock_timeout = :timeout"
        ).setParameter("timeout", lockTimeoutMs + "ms")
         .executeUpdate();

        try {
            // Acquire locks (may timeout)
            Account from = accountRepository.findByIdWithExclusiveLock(fromAccountId)
                .orElseThrow(() -> new AccountNotFoundException(fromAccountId));
            Account to = accountRepository.findByIdWithExclusiveLock(toAccountId)
                .orElseThrow(() -> new AccountNotFoundException(toAccountId));

            // Locks acquired, perform transfer
            from.debit(amount);
            to.credit(amount);

            accountRepository.save(from);
            accountRepository.save(to);

            return TransferResult.success(from, to, amount);

        } catch (PessimisticLockException e) {
            throw new LockAcquisitionException(
                "Could not acquire lock within " + lockTimeoutMs + "ms", e
            );
        }
    }
}
```

### Programmatic Locking with EntityManager

```java
@Service
public class EntityManagerLockingService {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public Account updateWithProgrammaticLock(Long accountId, BigDecimal newBalance) {
        // Find entity first
        Account account = entityManager.find(Account.class, accountId);

        // Acquire lock programmatically
        Map<String, Object> properties = new HashMap<>();
        properties.put("javax.persistence.lock.timeout", 5000); // 5 seconds

        entityManager.lock(account, LockModeType.PESSIMISTIC_WRITE, properties);

        // Now safe to modify
        account.setBalance(newBalance);
        return account;
    }
}
```

### Retry Logic for Lock Timeouts

```java
@Service
public class RetryablePessimisticService {

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 500;

    public TransferResult transferWithRetry(
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount
    ) {
        int attempt = 0;
        PessimisticLockException lastException = null;

        while (attempt < MAX_RETRIES) {
            try {
                return transferWithLock(fromAccountId, toAccountId, amount);
            } catch (PessimisticLockException e) {
                attempt++;
                lastException = e;

                if (attempt >= MAX_RETRIES) {
                    break;
                }

                // Exponential backoff
                long delayMs = RETRY_DELAY_MS * (long) Math.pow(2, attempt - 1);
                Thread.sleep(delayMs);

                log.warn("Lock timeout on attempt {}/{}, retrying after {}ms",
                    attempt, MAX_RETRIES, delayMs);
            }
        }

        throw new MaxLockRetriesExceededException(
            "Failed to acquire lock after " + MAX_RETRIES + " attempts",
            lastException
        );
    }

    @Transactional
    private TransferResult transferWithLock(
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount
    ) {
        // Lock acquisition (may throw PessimisticLockException)
    }
}
```

### Exception Handling

```java
@RestControllerAdvice
public class PessimisticLockExceptionHandler {

    @ExceptionHandler(PessimisticLockException.class)
    public ResponseEntity<ErrorResponse> handlePessimisticLock(
        PessimisticLockException ex,
        HttpServletRequest request
    ) {
        String correlationId = request.getHeader("X-Correlation-ID");

        ErrorResponse error = ErrorResponse.builder()
            .error("PessimisticLockException")
            .message("Could not acquire database lock")
            .retryable(true)
            .recommendation("Retry after short delay")
            .correlationId(correlationId)
            .timestamp(Instant.now())
            .build();

        log.warn("[{}] PessimisticLockException: {}",
            correlationId, ex.getMessage());

        return ResponseEntity.status(HttpStatus.LOCKED).body(error);
    }

    @ExceptionHandler(LockTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleLockTimeout(
        LockTimeoutException ex,
        HttpServletRequest request
    ) {
        String correlationId = request.getHeader("X-Correlation-ID");

        ErrorResponse error = ErrorResponse.builder()
            .error("LockTimeoutException")
            .message("Lock acquisition timeout exceeded")
            .retryable(true)
            .recommendation("Resource is busy, retry with backoff")
            .correlationId(correlationId)
            .timestamp(Instant.now())
            .build();

        log.warn("[{}] LockTimeoutException: {}",
            correlationId, ex.getMessage());

        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(error);
    }
}
```

## Lock Timeout Configuration

### PostgreSQL lock_timeout

```sql
-- Session-level (applies to current session)
SET lock_timeout = '5s';

-- Transaction-level (applies to current transaction only)
SET LOCAL lock_timeout = '2s';

-- Database-level (applies to all connections)
ALTER DATABASE mydb SET lock_timeout = '10s';

-- Cancel statement if lock not acquired within timeout
```

**JPA Configuration**:
```java
@Transactional
public void operationWithTimeout() {
    entityManager.createNativeQuery("SET LOCAL lock_timeout = '3s'")
        .executeUpdate();

    // Operations with 3-second lock timeout
}
```

### application.properties

```properties
# Default lock timeout (milliseconds)
spring.jpa.properties.javax.persistence.lock.timeout=5000

# PostgreSQL lock timeout
spring.jpa.properties.hibernate.dialect.lock_timeout=5000
```

## When to Use Pessimistic Locking

### ✅ Use Pessimistic Locking When:

1. **High contention**: Many concurrent updates to same row
   ```java
   // Ticket inventory (many users booking simultaneously)
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Transactional
   public Ticket reserveTicket(Long eventId) {
       Event event = eventRepository.findByIdWithExclusiveLock(eventId)
           .orElseThrow();

       if (event.getAvailableTickets() > 0) {
           event.decrementTickets();
           return createTicket(event);
       }

       throw new SoldOutException();
   }
   ```

2. **Critical operations that must succeed**: Can't afford retry failures
   ```java
   // Payment processing
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Transactional
   public Payment processPayment(Long accountId, BigDecimal amount) {
       Account account = accountRepository.findByIdWithExclusiveLock(accountId)
           .orElseThrow();

       // Guaranteed no concurrent modification
       account.debit(amount);
       return createPayment(account, amount);
   }
   ```

3. **Short transactions**: Can hold lock briefly without blocking others long
   ```java
   // Quick balance update
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Transactional
   public void updateBalance(Long accountId, BigDecimal delta) {
       Account account = accountRepository.findByIdWithExclusiveLock(accountId)
           .orElseThrow();
       account.adjustBalance(delta);
       accountRepository.save(account);
   }
   ```

4. **Sequential operations required**: Order matters
   ```java
   // Sequential transaction processing
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Transactional
   public void processNextTransaction() {
       Transaction txn = transactionRepository.findNextPending()
           .orElse(null);

       if (txn != null) {
           txn.setStatus("PROCESSING");
           process(txn);
       }
   }
   ```

### ❌ Don't Use Pessimistic Locking When:

1. **Low contention**: Conflicts are rare
   ```java
   // User profile updates (users rarely edit simultaneously)
   // Use optimistic locking instead
   ```

2. **Long-running transactions**: Holding locks too long blocks others
   ```java
   // ❌ Bad: Batch processing with locks
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Transactional
   public void processBatch(List<Long> ids) {
       for (Long id : ids) {
           // Holds lock for entire batch!
       }
   }

   // ✅ Good: Separate transactions
   public void processBatch(List<Long> ids) {
       for (Long id : ids) {
           processSingleItem(id);  // Each has own short transaction
       }
   }
   ```

3. **Distributed systems**: Can't hold database locks across services
   ```java
   // Microservices (use optimistic locking or distributed locks)
   ```

4. **Read-heavy workloads**: Locks reduce read concurrency
   ```java
   // Reporting queries (use optimistic locking or read-only transactions)
   ```

## Lock Monitoring

### View Current Locks

```bash
curl http://localhost:8080/api/demos/concurrency/pessimistic/locks
```

**Response**:
```json
{
  "activeLocks": [
    {
      "lockType": "ExclusiveLock",
      "mode": "RowExclusiveLock",
      "relation": "accounts",
      "pid": 12345,
      "transactionId": 501,
      "correlationId": "pessimistic-demo-001",
      "granted": true,
      "lockDuration": 3521
    }
  ],
  "blockedTransactions": [
    {
      "blockedPid": 12346,
      "blockingPid": 12345,
      "blockedQuery": "SELECT * FROM accounts WHERE id = 1 FOR UPDATE",
      "blockingCorrelationId": "pessimistic-demo-001",
      "waitDuration": 2103
    }
  ],
  "totalLocks": 1,
  "totalBlocked": 1
}
```

### PostgreSQL Lock Queries

```sql
-- View all locks
SELECT
    l.locktype,
    l.database,
    l.relation::regclass AS table,
    l.mode,
    l.granted,
    a.pid,
    a.query,
    a.state
FROM pg_locks l
JOIN pg_stat_activity a ON l.pid = a.pid
WHERE l.relation IS NOT NULL;

-- View blocked queries
SELECT
    blocked.pid AS blocked_pid,
    blocked_activity.query AS blocked_query,
    blocking.pid AS blocking_pid,
    blocking_activity.query AS blocking_query,
    blocked_activity.wait_event_type,
    blocked_activity.wait_event
FROM pg_locks blocked
JOIN pg_stat_activity blocked_activity ON blocked.pid = blocked_activity.pid
JOIN pg_locks blocking ON blocked.locktype = blocking.locktype
    AND blocked.database IS NOT DISTINCT FROM blocking.database
    AND blocked.relation IS NOT DISTINCT FROM blocking.relation
    AND blocked.pid != blocking.pid
JOIN pg_stat_activity blocking_activity ON blocking.pid = blocking_activity.pid
WHERE NOT blocked.granted
    AND blocking.granted;
```

### Metrics

```promql
# Lock wait time
histogram_quantile(0.95,
  rate(lock_wait_duration_seconds_bucket[5m])
)

# Lock timeout rate
rate(lock_timeouts[5m])

# Blocked transaction count
sum(blocked_transactions)

# Lock contention by table
sum by (table) (lock_waits)
```

## Comparison: Pessimistic vs Optimistic

| Aspect | Pessimistic Locking | Optimistic Locking |
|--------|--------------------|--------------------|
| **Strategy** | Prevent conflicts (lock) | Detect conflicts (version) |
| **Concurrency** | Low (serialized) | High (no locks) |
| **Performance** | Slower (lock overhead) | Faster (no locks) |
| **Conflicts** | Prevented | Detected, requires retry |
| **Best for** | High contention | Low contention |
| **Blocking** | Yes (waits for lock) | No |
| **Deadlocks** | Possible | Never |
| **Retry logic** | Optional | Required |
| **Long transactions** | Not suitable | Suitable |

## Best Practices

### 1. Always Set Lock Timeout

```java
// ✅ Good: Timeout prevents indefinite waiting
entityManager.createNativeQuery("SET LOCAL lock_timeout = '5s'")
    .executeUpdate();

// ❌ Bad: No timeout, may wait forever
```

### 2. Acquire Locks in Consistent Order

```java
// ✅ Good: Consistent order (prevents deadlocks)
Long firstId = Math.min(fromId, toId);
Long secondId = Math.max(fromId, toId);

Account first = accountRepository.findByIdWithExclusiveLock(firstId).orElseThrow();
Account second = accountRepository.findByIdWithExclusiveLock(secondId).orElseThrow();

// ❌ Bad: Inconsistent order (deadlock risk)
Account from = accountRepository.findByIdWithExclusiveLock(fromId).orElseThrow();
Account to = accountRepository.findByIdWithExclusiveLock(toId).orElseThrow();
```

### 3. Keep Lock Duration Short

```java
// ✅ Good: Lock → Modify → Commit (fast)
@Transactional
public void quickUpdate(Long id) {
    Account account = accountRepository.findByIdWithExclusiveLock(id).orElseThrow();
    account.setBalance(newBalance);
    accountRepository.save(account);
}  // Lock released

// ❌ Bad: Lock held during external API call
@Transactional
public void slowUpdate(Long id) {
    Account account = accountRepository.findByIdWithExclusiveLock(id).orElseThrow();
    externalApiCall();  // Lock held during network I/O!
    account.setBalance(newBalance);
}
```

### 4. Use Shared Locks for Read-Only Operations

```java
// ✅ Good: Shared lock allows concurrent readers
@Lock(LockModeType.PESSIMISTIC_READ)
public Account readForReporting(Long id) {
    return accountRepository.findByIdWithSharedLock(id).orElseThrow();
}

// ❌ Bad: Exclusive lock blocks other readers
@Lock(LockModeType.PESSIMISTIC_WRITE)
public Account readForReporting(Long id) {
    return accountRepository.findByIdWithExclusiveLock(id).orElseThrow();
}
```

### 5. Implement Retry Logic for Timeouts

```java
@Retryable(
    value = {PessimisticLockException.class, LockTimeoutException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 500, multiplier = 2)
)
@Transactional
public void operationWithRetry() {
    // Auto-retry on lock timeout
}
```

### 6. Monitor Lock Contention

```java
@Aspect
@Component
public class LockMonitor {

    @Around("@annotation(org.springframework.data.jpa.repository.Lock)")
    public Object monitorLock(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } catch (PessimisticLockException e) {
            meterRegistry.counter("lock_timeouts").increment();
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - start;
            meterRegistry.timer("lock_wait_time").record(duration, TimeUnit.MILLISECONDS);
        }
    }
}
```

## Summary

This demonstration shows:

✅ JPA @Lock annotation for pessimistic locking
✅ Different lock types (PESSIMISTIC_READ, PESSIMISTIC_WRITE, PESSIMISTIC_FORCE_INCREMENT)
✅ Lock timeout configuration and handling
✅ Lock monitoring and contention detection
✅ When to use pessimistic vs optimistic locking
✅ Best practices for preventing deadlocks

**Key Takeaways**:
- Pessimistic locking prevents conflicts by acquiring locks first
- Use for high-contention scenarios where conflicts are likely
- Always set lock timeouts to prevent indefinite waiting
- Acquire locks in consistent order to prevent deadlocks
- Keep lock duration short to maximize concurrency
- Monitor lock contention to identify bottlenecks

For more demonstrations:
- [Optimistic Locking](./optimistic-locking.md) - Version-based concurrency
- [Deadlocks](./deadlocks.md) - Deadlock detection and prevention
- [Isolation Levels](../acid/isolation.md) - Transaction isolation

## References

- [JPA Locking](https://docs.oracle.com/javaee/7/tutorial/persistence-locking.htm)
- [PostgreSQL Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html)
- [Hibernate Locking](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#locking)
- [Lock Modes](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE)
