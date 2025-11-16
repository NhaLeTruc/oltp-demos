# Optimistic Locking Demonstration

**Demonstration**: Version-based concurrency control and lost update prevention

**User Story**: US2 - Concurrency and Conflict Handling (T106)

**Functional Requirements**: FR-009

## Overview

Optimistic locking assumes that conflicts between concurrent transactions are rare. Instead of locking rows, it uses a version field to detect when multiple transactions modify the same data. If a conflict is detected at commit time, one transaction fails with an `OptimisticLockException`.

### What You'll Learn

- JPA @Version annotation for optimistic locking
- Lost update prevention
- OptimisticLockException handling and retry logic
- Version-based conflict detection
- When to use optimistic vs pessimistic locking
- Best practices for high-concurrency scenarios

### Prerequisites

- Application running on `localhost:8080`
- PostgreSQL database initialized
- Understanding of concurrent transactions
- Multiple terminal windows for parallel requests

## Optimistic Locking Concept

```
┌────────────────────────────────────────────────────┐
│ Optimistic Locking: "Hope for the best"           │
│                                                     │
│ Transaction A              Transaction B           │
│ ─────────────              ─────────────           │
│                                                     │
│ Read account (v=1)         Read account (v=1)      │
│ balance = 1000             balance = 1000          │
│ ↓                          ↓                       │
│ Modify balance             Modify balance          │
│ new balance = 900          new balance = 800       │
│ ↓                          ↓                       │
│ COMMIT                     COMMIT                  │
│ UPDATE ... WHERE id=1      UPDATE ... WHERE id=1   │
│   AND version=1              AND version=1         │
│ SET balance=900,           SET balance=800,        │
│     version=2                  version=2           │
│ ✓ SUCCESS (v=1→2)          ✗ FAIL (v=2, not 1)    │
│                            OptimisticLockException │
│                                                     │
│ Result: Transaction A wins, B must retry          │
└────────────────────────────────────────────────────┘

vs Pessimistic Locking: "Lock it first"
┌────────────────────────────────────────────────────┐
│ Transaction A              Transaction B           │
│ ─────────────              ─────────────           │
│                                                     │
│ SELECT ... FOR UPDATE      SELECT ... FOR UPDATE   │
│ ✓ Lock acquired           ⏳ Blocked (waiting)    │
│ Modify balance                                     │
│ COMMIT                                             │
│ ✓ Lock released           ✓ Lock acquired         │
│                            Modify balance          │
│                            COMMIT                  │
│                                                     │
│ Result: Sequential execution, no conflicts        │
└────────────────────────────────────────────────────┘
```

## JPA @Version Implementation

### Entity with Version Field

```java
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String accountNumber;

    @Column(nullable = false)
    private BigDecimal balance;

    private String status;

    @Version  // Optimistic locking
    @Column(nullable = false)
    private Long version;

    // Getters and setters
}
```

### Database Schema

```sql
CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    account_number VARCHAR(50) NOT NULL,
    balance DECIMAL(15, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,  -- Version field
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### How It Works

**JPA automatically**:
1. Includes `version` in SELECT queries
2. Increments version on UPDATE
3. Adds `WHERE version = ?` to UPDATE statements
4. Throws `OptimisticLockException` if no rows updated

**Generated SQL**:
```sql
-- Read
SELECT id, balance, version FROM accounts WHERE id = 1;
-- Returns: id=1, balance=1000, version=5

-- Update (Transaction A)
UPDATE accounts
SET balance = 900, version = 6
WHERE id = 1 AND version = 5;
-- Rows affected: 1 (success)

-- Update (Transaction B, concurrent)
UPDATE accounts
SET balance = 800, version = 6
WHERE id = 1 AND version = 5;
-- Rows affected: 0 (version already 6, not 5)
-- OptimisticLockException thrown
```

## Demonstrations

### 1. Successful Concurrent Updates (No Conflict)

**Scenario**: Two transactions update different accounts

**Terminal 1**:
```bash
curl -X POST http://localhost:8080/api/demos/concurrency/optimistic/transfer \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: optimistic-demo-001' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00
  }'
```

**Terminal 2** (different accounts):
```bash
curl -X POST http://localhost:8080/api/demos/concurrency/optimistic/transfer \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: optimistic-demo-002' \
  -d '{
    "fromAccountId": 3,
    "toAccountId": 4,
    "amount": 200.00
  }'
```

**Both succeed**:
```json
{
  "success": true,
  "correlationId": "optimistic-demo-001",
  "transactionId": 501,
  "versionBefore": 5,
  "versionAfter": 6,
  "message": "Transfer completed without conflict"
}
```

**Key Points**:
- ✅ Different accounts, no version conflicts
- ✅ Both transactions commit successfully
- ✅ Versions incremented independently

### 2. Lost Update Prevention (Conflict Detection)

**Scenario**: Two transactions update the same account simultaneously

**Terminal 1**:
```bash
# Start transfer with artificial delay
curl -X POST http://localhost:8080/api/demos/concurrency/optimistic/transfer-with-delay \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: optimistic-demo-003' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00,
    "delayBeforeCommit": 5000
  }'
```

**Terminal 2** (execute within 5 seconds, same source account):
```bash
curl -X POST http://localhost:8080/api/demos/concurrency/optimistic/transfer \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID': optimistic-demo-004' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 3,
    "amount": 50.00
  }'
```

**Terminal 1 Response** (first to commit):
```json
{
  "success": true,
  "correlationId": "optimistic-demo-003",
  "transactionId": 502,
  "accountId": 1,
  "balanceBefore": "1000.00",
  "balanceAfter": "900.00",
  "versionBefore": 10,
  "versionAfter": 11,
  "message": "Transfer completed successfully"
}
```

**Terminal 2 Response** (conflict detected):
```json
{
  "success": false,
  "correlationId": "optimistic-demo-004",
  "error": "OptimisticLockException",
  "message": "Row was updated or deleted by another transaction",
  "accountId": 1,
  "expectedVersion": 10,
  "actualVersion": 11,
  "retryable": true,
  "recommendation": "Retry transaction with updated data"
}
```

**Key Points**:
- ✅ First transaction commits successfully
- ✅ Second transaction detects conflict (version changed)
- ✅ OptimisticLockException prevents lost update
- ✅ Application can retry with fresh data

### 3. Automatic Retry on Conflict

**Scenario**: Retry logic handles conflicts transparently

```bash
curl -X POST http://localhost:8080/api/demos/concurrency/optimistic/transfer-with-retry \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: optimistic-demo-005' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00,
    "maxRetries": 3
  }'
```

**Response** (after retry):
```json
{
  "success": true,
  "correlationId": "optimistic-demo-005",
  "transactionId": 503,
  "attempts": 2,
  "retriesNeeded": 1,
  "finalVersion": 12,
  "message": "Transfer completed after 1 retry due to version conflict"
}
```

**Application Logs**:
```
2025-11-16 10:15:23.456 [optimistic-demo-005] INFO  OptimisticService - Starting transfer: account=1, amount=100
2025-11-16 10:15:23.489 [optimistic-demo-005] WARN  OptimisticService - OptimisticLockException on attempt 1: version conflict
2025-11-16 10:15:23.490 [optimistic-demo-005] INFO  OptimisticService - Retrying (attempt 2/3)
2025-11-16 10:15:23.521 [optimistic-demo-005] INFO  OptimisticService - Transfer completed successfully on attempt 2
```

**Key Points**:
- ✅ Automatic retry on OptimisticLockException
- ✅ Transparent to caller (appears as single operation)
- ✅ Exponential backoff prevents thundering herd
- ✅ Max retries prevents infinite loops

## Implementation Details

### Retry Logic with Spring Retry

```java
@Service
public class OptimisticTransferService {

    private static final int MAX_RETRIES = 3;

    @Retryable(
        value = {OptimisticLockException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @Transactional
    public TransferResult transferWithRetry(
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount
    ) {
        Account from = accountRepository.findById(fromAccountId)
            .orElseThrow(() -> new AccountNotFoundException(fromAccountId));
        Account to = accountRepository.findById(toAccountId)
            .orElseThrow(() -> new AccountNotFoundException(toAccountId));

        // Validate
        if (from.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(from.getId(), amount);
        }

        // Execute transfer
        from.debit(amount);
        to.credit(amount);

        // Save (version check happens here)
        accountRepository.save(from);  // May throw OptimisticLockException
        accountRepository.save(to);

        return TransferResult.success(from, to, amount);
    }

    @Recover
    public TransferResult recover(
        OptimisticLockException e,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount
    ) {
        log.error("Failed after {} retries: {}", MAX_RETRIES, e.getMessage());
        throw new MaxRetriesExceededException(
            "Transfer failed after " + MAX_RETRIES + " attempts due to version conflicts"
        );
    }
}
```

### Manual Retry Logic

```java
@Service
public class ManualRetryService {

    public TransferResult transferWithManualRetry(
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        int maxRetries
    ) {
        int attempt = 0;
        OptimisticLockException lastException = null;

        while (attempt < maxRetries) {
            try {
                return executeTransfer(fromAccountId, toAccountId, amount);
            } catch (OptimisticLockException e) {
                attempt++;
                lastException = e;

                if (attempt >= maxRetries) {
                    break;
                }

                // Exponential backoff
                long backoffMs = (long) Math.pow(2, attempt) * 100;
                Thread.sleep(backoffMs);

                log.warn("OptimisticLockException on attempt {}/{}, retrying after {}ms",
                    attempt, maxRetries, backoffMs);
            }
        }

        throw new MaxRetriesExceededException(
            "Failed after " + maxRetries + " attempts", lastException
        );
    }

    @Transactional
    private TransferResult executeTransfer(
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount
    ) {
        // Transfer logic (may throw OptimisticLockException)
    }
}
```

### Exception Handling

```java
@RestControllerAdvice
public class OptimisticLockExceptionHandler {

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
        OptimisticLockException ex,
        HttpServletRequest request
    ) {
        String correlationId = request.getHeader("X-Correlation-ID");

        ErrorResponse error = ErrorResponse.builder()
            .error("OptimisticLockException")
            .message("Row was updated or deleted by another transaction")
            .retryable(true)
            .recommendation("Retry transaction with updated data")
            .correlationId(correlationId)
            .timestamp(Instant.now())
            .build();

        log.warn("[{}] OptimisticLockException: {}",
            correlationId, ex.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }
}
```

## When to Use Optimistic Locking

### ✅ Use Optimistic Locking When:

1. **Low contention**: Conflicts are rare
   ```java
   // User profile updates (users rarely edit simultaneously)
   @Transactional
   public void updateProfile(Long userId, ProfileData data) {
       User user = userRepository.findById(userId).orElseThrow();
       user.setProfile(data);
       userRepository.save(user);  // Optimistic lock check
   }
   ```

2. **Read-heavy workloads**: Many reads, few writes
   ```java
   // Product catalog (frequently read, rarely updated)
   @Transactional
   public void updateProductPrice(Long productId, BigDecimal newPrice) {
       Product product = productRepository.findById(productId).orElseThrow();
       product.setPrice(newPrice);
       productRepository.save(product);
   }
   ```

3. **Long-running transactions**: Holding locks is too expensive
   ```java
   // Batch processing with user review
   public void processBatchWithReview(List<Long> ids) {
       // User reviews data (minutes)
       List<Account> accounts = loadAccounts(ids);
       displayForReview(accounts);

       // User approves
       // Use optimistic locking to detect changes during review
       saveAccounts(accounts);  // May fail if data changed
   }
   ```

4. **Distributed systems**: Can't hold database locks across services
   ```java
   // Microservices with eventual consistency
   @Transactional
   public void updateInventory(Long productId, int quantity) {
       Product product = productRepository.findById(productId).orElseThrow();
       product.setQuantity(quantity);
       productRepository.save(product);  // Version check
   }
   ```

### ❌ Don't Use Optimistic Locking When:

1. **High contention**: Many concurrent updates to same row
   ```java
   // ❌ Bad: Ticket inventory (many users booking simultaneously)
   // Use pessimistic locking instead

   // ✅ Good approach:
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   public Event reserveTicket(Long eventId) {
       Event event = eventRepository.findById(eventId).orElseThrow();
       if (event.getAvailableTickets() > 0) {
           event.decrementTickets();
           return eventRepository.save(event);
       }
       throw new SoldOutException();
   }
   ```

2. **Critical operations that must succeed**: Can't afford retries
   ```java
   // ❌ Bad: Payment processing (can't retry endlessly)
   // Use pessimistic locking or serializable isolation
   ```

3. **Side effects on retry**: Non-idempotent operations
   ```java
   // ❌ Bad: Sending emails in transaction
   @Transactional
   public void processOrder(Long orderId) {
       Order order = orderRepository.findById(orderId).orElseThrow();
       order.setStatus("CONFIRMED");
       sendConfirmationEmail(order);  // Sent multiple times on retry!
       orderRepository.save(order);
   }

   // ✅ Good: Side effects after commit
   @Transactional
   public void processOrder(Long orderId) {
       Order order = orderRepository.findById(orderId).orElseThrow();
       order.setStatus("CONFIRMED");
       orderRepository.save(order);
   }
   // Send email after commit in separate method
   ```

## Comparison: Optimistic vs Pessimistic

| Aspect | Optimistic Locking | Pessimistic Locking |
|--------|-------------------|---------------------|
| **Strategy** | Detect conflicts at commit | Prevent conflicts with locks |
| **Performance** | Fast (no locks) | Slower (lock overhead) |
| **Concurrency** | High (no blocking) | Low (serialized) |
| **Conflicts** | Detected, requires retry | Prevented, no retry |
| **Best for** | Low contention | High contention |
| **Complexity** | Requires retry logic | Simpler (no retries) |
| **Deadlocks** | Never | Possible |
| **Long transactions** | Suitable | Not suitable |

## Monitoring and Metrics

### Optimistic Lock Failure Rate

```promql
# Failure rate
rate(optimistic_lock_exceptions[5m])

# Retry rate
rate(optimistic_lock_retries[5m])

# Success rate after retry
rate(optimistic_lock_success_after_retry[5m]) /
rate(optimistic_lock_exceptions[5m])

# Accounts with most conflicts
topk(10, sum by (account_id) (
  increase(optimistic_lock_exceptions{entity="Account"}[1h])
))
```

### Application Logs

```
2025-11-16 10:15:23.456 [req-001] INFO  OptimisticService - Transfer started: from=1, to=2, amount=100
2025-11-16 10:15:23.489 [req-001] WARN  OptimisticService - OptimisticLockException: account=1, expectedVersion=5, actualVersion=6
2025-11-16 10:15:23.590 [req-001] INFO  OptimisticService - Retry successful on attempt 2
2025-11-16 10:15:23.591 [req-001] INFO  MetricsService - optimistic_lock_retry_count{account_id=1}=1
```

## Best Practices

### 1. Always Implement Retry Logic

```java
// ✅ Good
@Retryable(value = OptimisticLockException.class, maxAttempts = 3)
@Transactional
public void update(Long id, Data data) { }

// ❌ Bad: No retry
@Transactional
public void update(Long id, Data data) { }
```

### 2. Use Exponential Backoff

```java
@Backoff(delay = 100, multiplier = 2, maxDelay = 1000)
// Delays: 100ms, 200ms, 400ms, 800ms, 1000ms
```

### 3. Limit Retry Attempts

```java
@Retryable(maxAttempts = 3)  // Don't retry forever
```

### 4. Clear Entity Manager Before Retry

```java
catch (OptimisticLockException e) {
    entityManager.clear();  // Clear stale entities
    // Retry will fetch fresh data
}
```

### 5. Monitor Conflict Hotspots

```java
@Aspect
@Component
public class OptimisticLockMonitor {

    @AfterThrowing(
        pointcut = "execution(* com.demo.service.*.*(..))",
        throwing = "ex"
    )
    public void logOptimisticLockException(JoinPoint joinPoint, OptimisticLockException ex) {
        meterRegistry.counter("optimistic_lock_exceptions",
            "method", joinPoint.getSignature().getName()
        ).increment();
    }
}
```

## Summary

This demonstration shows:

✅ JPA @Version annotation for optimistic locking
✅ Lost update prevention through version checking
✅ OptimisticLockException handling and retry logic
✅ When to use optimistic vs pessimistic locking
✅ Best practices for high-concurrency scenarios
✅ Monitoring and metrics for conflict detection

**Key Takeaways**:
- Optimistic locking detects conflicts rather than preventing them
- Works best for low-contention scenarios
- Requires retry logic for reliability
- Provides higher concurrency than pessimistic locking
- Monitor conflict rates to identify hotspots

For more demonstrations:
- [Pessimistic Locking](./pessimistic-locking.md) - Lock-based concurrency control
- [Deadlocks](./deadlocks.md) - Deadlock detection and prevention
- [Isolation Levels](../acid/isolation.md) - Transaction isolation

## References

- [JPA Optimistic Locking](https://docs.oracle.com/javaee/7/tutorial/persistence-locking002.htm)
- [Spring Retry](https://docs.spring.io/spring-retry/docs/current/reference/html/)
- [Hibernate Versioning](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#locking-optimistic)
- [Lost Update Problem](https://en.wikipedia.org/wiki/Write%E2%80%93write_conflict)
