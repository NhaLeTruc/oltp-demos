# ACID Isolation Demonstration

**Demonstration**: Transaction isolation levels and concurrent transaction handling

**User Story**: US1 - ACID Transaction Guarantees (T078)

**Functional Requirements**: FR-003

## Overview

Isolation is the "I" in ACID and ensures that concurrent transactions execute without interfering with each other. Different isolation levels provide different guarantees about what concurrent transactions can see.

### What You'll Learn

- PostgreSQL isolation levels (READ COMMITTED, REPEATABLE READ, SERIALIZABLE)
- Concurrency phenomena (dirty reads, non-repeatable reads, phantom reads)
- Spring @Transactional isolation configuration
- Locking behavior and transaction visibility
- Serialization failures and retry logic
- Isolation level trade-offs

### Prerequisites

- Application running on `localhost:8080`
- PostgreSQL database initialized
- Understanding of concurrent transactions
- Multiple terminal windows for parallel requests

## Isolation Levels

```
┌────────────────────────────────────────────────────────┐
│ Isolation Level    │ Dirty │ Non-Repeatable │ Phantom │
│                    │ Read  │ Read           │ Read    │
├────────────────────┼───────┼────────────────┼─────────┤
│ READ UNCOMMITTED   │  Yes  │      Yes       │   Yes   │
│ READ COMMITTED     │  No   │      Yes       │   Yes   │
│ REPEATABLE READ    │  No   │      No        │   Yes*  │
│ SERIALIZABLE       │  No   │      No        │   No    │
└────────────────────────────────────────────────────────┘

* PostgreSQL REPEATABLE READ prevents phantom reads
  (stricter than SQL standard)

PostgreSQL Default: READ COMMITTED
Spring Default: Database default (READ COMMITTED)
```

## Concurrency Phenomena

### 1. Dirty Read

**Definition**: Reading uncommitted changes from another transaction

```
Time    Transaction A              Transaction B
────────────────────────────────────────────────
T1      BEGIN;
T2      UPDATE accounts            BEGIN;
         SET balance = 1000
         WHERE id = 1;
T3                                 SELECT balance FROM accounts
                                    WHERE id = 1;
                                   -- Reads 1000 (uncommitted!)
T4      ROLLBACK;
T5                                 -- Value reverts to original
                                   -- Transaction B saw phantom data
```

**PostgreSQL**: Dirty reads **never occur** (even at READ UNCOMMITTED, PostgreSQL upgrades to READ COMMITTED)

### 2. Non-Repeatable Read

**Definition**: Reading different values in the same transaction

```
Time    Transaction A              Transaction B
────────────────────────────────────────────────
T1      BEGIN;                     BEGIN;
T2      SELECT balance FROM accounts
         WHERE id = 1;
        -- Reads 1000
T3                                 UPDATE accounts
                                    SET balance = 2000
                                    WHERE id = 1;
                                   COMMIT;
T4      SELECT balance FROM accounts
         WHERE id = 1;
        -- Reads 2000 (different value!)
T5      COMMIT;
```

**Occurs at**: READ COMMITTED
**Prevented by**: REPEATABLE READ, SERIALIZABLE

### 3. Phantom Read

**Definition**: Seeing different rows in the same query

```
Time    Transaction A              Transaction B
────────────────────────────────────────────────
T1      BEGIN;                     BEGIN;
T2      SELECT COUNT(*) FROM accounts
         WHERE balance > 5000;
        -- Returns 10
T3                                 INSERT INTO accounts
                                    (balance) VALUES (10000);
                                   COMMIT;
T4      SELECT COUNT(*) FROM accounts
         WHERE balance > 5000;
        -- Returns 11 (phantom row!)
T5      COMMIT;
```

**Occurs at**: READ COMMITTED
**Prevented by**: REPEATABLE READ (in PostgreSQL), SERIALIZABLE

## Demonstrations

### 1. Non-Repeatable Read (READ COMMITTED)

**Scenario**: Two transactions see different account balances

**Terminal 1** (Transaction A):
```bash
# Start long-running transaction
curl -X POST http://localhost:8080/api/demos/acid/isolation/non-repeatable-read \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: isolation-demo-001' \
  -d '{
    "accountId": 1,
    "isolationLevel": "READ_COMMITTED",
    "sleepBetweenReads": 5000
  }'
```

**Terminal 2** (Transaction B - execute within 5 seconds):
```bash
# Update account while Transaction A is running
curl -X POST http://localhost:8080/api/demos/acid/atomicity/transfer \
  -H 'Content-Type: application/json' \
  -d '{
    "fromAccountId": 2,
    "toAccountId": 1,
    "amount": 500.00
  }'
```

**Terminal 1 Response** (after Transaction A completes):
```json
{
  "success": true,
  "correlationId": "isolation-demo-001",
  "isolationLevel": "READ_COMMITTED",
  "accountId": 1,
  "firstRead": {
    "balance": "1000.00",
    "timestamp": "2025-11-16T10:00:00Z"
  },
  "secondRead": {
    "balance": "1500.00",
    "timestamp": "2025-11-16T10:00:05Z"
  },
  "balanceChanged": true,
  "phenomenon": "NON_REPEATABLE_READ",
  "message": "Value changed between reads within same transaction"
}
```

**Key Points**:
- ✅ Transaction A read balance twice
- ✅ Transaction B committed between reads
- ✅ Second read saw committed changes (non-repeatable read)
- ✅ This is expected behavior at READ COMMITTED

### 2. Repeatable Read Prevention

**Scenario**: Same test with REPEATABLE READ isolation

**Terminal 1**:
```bash
curl -X POST http://localhost:8080/api/demos/acid/isolation/non-repeatable-read \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: isolation-demo-002' \
  -d '{
    "accountId": 1,
    "isolationLevel": "REPEATABLE_READ",
    "sleepBetweenReads": 5000
  }'
```

**Terminal 2** (execute within 5 seconds):
```bash
curl -X POST http://localhost:8080/api/demos/acid/atomicity/transfer \
  -H 'Content-Type: application/json' \
  -d '{
    "fromAccountId": 2,
    "toAccountId": 1,
    "amount": 500.00
  }'
```

**Terminal 1 Response**:
```json
{
  "success": true,
  "correlationId": "isolation-demo-002",
  "isolationLevel": "REPEATABLE_READ",
  "accountId": 1,
  "firstRead": {
    "balance": "1000.00",
    "timestamp": "2025-11-16T10:05:00Z"
  },
  "secondRead": {
    "balance": "1000.00",
    "timestamp": "2025-11-16T10:05:05Z"
  },
  "balanceChanged": false,
  "phenomenon": "NONE",
  "message": "Consistent snapshot - other transaction's changes not visible"
}
```

**Key Points**:
- ✅ Both reads returned same value
- ✅ Transaction B's commit not visible to Transaction A
- ✅ REPEATABLE READ provides snapshot isolation
- ✅ Transaction sees consistent database snapshot

### 3. Phantom Read Prevention

**Scenario**: Query sees different rows between executions

**Terminal 1**:
```bash
# Count accounts with balance > 5000
curl -X POST http://localhost:8080/api/demos/acid/isolation/phantom-read \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: isolation-demo-003' \
  -d '{
    "balanceThreshold": 5000.00,
    "isolationLevel": "READ_COMMITTED",
    "sleepBetweenQueries": 5000
  }'
```

**Terminal 2** (execute within 5 seconds):
```bash
# Create new account with high balance
curl -X POST http://localhost:8080/api/accounts \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": 1,
    "accountTypeId": 1,
    "balance": 10000.00,
    "status": "ACTIVE"
  }'
```

**Terminal 1 Response** (READ COMMITTED - phantom occurs):
```json
{
  "success": true,
  "correlationId": "isolation-demo-003",
  "isolationLevel": "READ_COMMITTED",
  "balanceThreshold": 5000.00,
  "firstQuery": {
    "count": 10,
    "timestamp": "2025-11-16T10:10:00Z"
  },
  "secondQuery": {
    "count": 11,
    "timestamp": "2025-11-16T10:10:05Z"
  },
  "countChanged": true,
  "phenomenon": "PHANTOM_READ",
  "message": "New row appeared between queries (phantom read)"
}
```

**With REPEATABLE READ** (phantom prevented):
```json
{
  "success": true,
  "isolationLevel": "REPEATABLE_READ",
  "firstQuery": {
    "count": 10
  },
  "secondQuery": {
    "count": 10
  },
  "countChanged": false,
  "phenomenon": "NONE",
  "message": "Consistent snapshot - new rows not visible"
}
```

### 4. Serialization Failure

**Scenario**: Two transactions conflict under SERIALIZABLE isolation

**Terminal 1**:
```bash
# Start SERIALIZABLE transaction
curl -X POST http://localhost:8080/api/demos/acid/isolation/serialization-conflict \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: isolation-demo-004' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00,
    "isolationLevel": "SERIALIZABLE",
    "delayBeforeCommit": 3000
  }'
```

**Terminal 2** (execute immediately):
```bash
# Conflicting SERIALIZABLE transaction
curl -X POST http://localhost:8080/api/demos/acid/isolation/serialization-conflict \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: isolation-demo-005' \
  -d '{
    "fromAccountId": 2,
    "toAccountId": 1,
    "amount": 50.00,
    "isolationLevel": "SERIALIZABLE",
    "delayBeforeCommit": 3000
  }'
```

**One transaction succeeds**:
```json
{
  "success": true,
  "correlationId": "isolation-demo-004",
  "transactionId": 201,
  "message": "Transfer completed successfully"
}
```

**Other transaction fails with serialization error**:
```json
{
  "success": false,
  "correlationId": "isolation-demo-005",
  "error": "SerializationException",
  "sqlState": "40001",
  "message": "Could not serialize access due to concurrent update",
  "retryable": true,
  "recommendation": "Retry transaction with exponential backoff"
}
```

**Key Points**:
- ✅ SERIALIZABLE detects read-write conflicts
- ✅ One transaction aborts with serialization error
- ✅ Application must implement retry logic
- ✅ Ensures true serializability

## Implementation Details

### Spring @Transactional Isolation

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public Account readCommittedExample(Long accountId) {
    // Default PostgreSQL behavior
    // Sees all committed changes
    // Non-repeatable reads possible
    return accountRepository.findById(accountId).orElseThrow();
}

@Transactional(isolation = Isolation.REPEATABLE_READ)
public Account repeatableReadExample(Long accountId) {
    // Consistent snapshot
    // Same query returns same results
    // Prevents non-repeatable reads
    return accountRepository.findById(accountId).orElseThrow();
}

@Transactional(isolation = Isolation.SERIALIZABLE)
public void serializableExample(Long fromId, Long toId, BigDecimal amount) {
    // Strictest isolation
    // Detects read-write conflicts
    // May throw SerializationException
    // Application must retry
    Account from = accountRepository.findById(fromId).orElseThrow();
    Account to = accountRepository.findById(toId).orElseThrow();

    from.debit(amount);
    to.credit(amount);

    accountRepository.save(from);
    accountRepository.save(to);
    // Commit may fail with serialization error
}
```

### Retry Logic for Serialization Failures

```java
@Service
public class TransferService {

    private static final int MAX_RETRIES = 3;

    public TransferResult transferWithRetry(
        Long fromId,
        Long toId,
        BigDecimal amount
    ) {
        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try {
                return executeSerializableTransfer(fromId, toId, amount);
            } catch (SerializationException e) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    throw new MaxRetriesExceededException(
                        "Failed after " + MAX_RETRIES + " attempts", e
                    );
                }

                // Exponential backoff
                long backoffMs = (long) Math.pow(2, attempt) * 100;
                Thread.sleep(backoffMs);

                log.warn("Serialization failure, retry {}/{}: {}",
                    attempt, MAX_RETRIES, e.getMessage());
            }
        }
        throw new IllegalStateException("Should not reach here");
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    private TransferResult executeSerializableTransfer(
        Long fromId,
        Long toId,
        BigDecimal amount
    ) {
        // Transfer logic here
        // May throw SerializationException (40001)
    }
}
```

### Detecting Isolation Phenomena

```java
@Service
public class IsolationDemoService {

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public NonRepeatableReadResult demonstrateNonRepeatableRead(
        Long accountId,
        long sleepMillis
    ) {
        // First read
        Account account1 = accountRepository.findById(accountId).orElseThrow();
        BigDecimal balance1 = account1.getBalance();
        Instant timestamp1 = Instant.now();

        // Sleep to allow concurrent modification
        Thread.sleep(sleepMillis);

        // Second read (same transaction)
        entityManager.clear(); // Clear cache to force DB read
        Account account2 = accountRepository.findById(accountId).orElseThrow();
        BigDecimal balance2 = account2.getBalance();
        Instant timestamp2 = Instant.now();

        boolean changed = !balance1.equals(balance2);

        return NonRepeatableReadResult.builder()
            .firstRead(new ReadResult(balance1, timestamp1))
            .secondRead(new ReadResult(balance2, timestamp2))
            .balanceChanged(changed)
            .phenomenon(changed ? "NON_REPEATABLE_READ" : "NONE")
            .build();
    }
}
```

## PostgreSQL Locking Behavior

### Row-Level Locks

```sql
-- FOR UPDATE: Exclusive lock for updates
BEGIN;
SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
-- Blocks other FOR UPDATE on same row
-- Allows SELECT without FOR UPDATE
UPDATE accounts SET balance = 1000 WHERE id = 1;
COMMIT;

-- FOR SHARE: Shared lock for reads
BEGIN;
SELECT * FROM accounts WHERE id = 1 FOR SHARE;
-- Allows other FOR SHARE
-- Blocks FOR UPDATE and UPDATE
COMMIT;
```

### Lock Monitoring

```bash
# View current locks
curl http://localhost:8080/api/demos/acid/isolation/locks
```

**Response**:
```json
{
  "activeLocks": [
    {
      "lockType": "RowExclusiveLock",
      "relation": "accounts",
      "pid": 1234,
      "transactionId": 501,
      "granted": true,
      "fastpath": false
    }
  ],
  "blockedQueries": [
    {
      "blockedPid": 1235,
      "blockingPid": 1234,
      "blockedQuery": "UPDATE accounts SET balance = ...",
      "waitingDuration": "00:00:03.521"
    }
  ]
}
```

## Isolation Level Trade-offs

### READ COMMITTED

**Pros**:
- ✅ Lowest lock contention
- ✅ Best performance
- ✅ Suitable for most applications
- ✅ PostgreSQL default

**Cons**:
- ❌ Non-repeatable reads possible
- ❌ Phantom reads possible
- ❌ Report generation may see inconsistent data

**Use Cases**:
- Standard OLTP operations
- Single-row updates
- Short transactions

### REPEATABLE READ

**Pros**:
- ✅ Consistent snapshot within transaction
- ✅ Prevents non-repeatable reads
- ✅ Prevents phantom reads (PostgreSQL)
- ✅ Good for reports and analytics

**Cons**:
- ❌ Higher lock contention
- ❌ Serialization failures possible
- ❌ Slightly lower performance

**Use Cases**:
- Financial reports
- Multi-step calculations
- Batch processing
- Audit queries

### SERIALIZABLE

**Pros**:
- ✅ Strongest isolation guarantee
- ✅ True serializability
- ✅ Simplifies reasoning about concurrency
- ✅ Prevents all anomalies

**Cons**:
- ❌ Frequent serialization failures
- ❌ Requires retry logic
- ❌ Lowest performance
- ❌ Not suitable for high contention

**Use Cases**:
- Critical financial transactions
- Inventory management with strict consistency
- Operations requiring absolute correctness

## Monitoring Isolation Issues

### Metrics

```promql
# Serialization failure rate
rate(transaction_failures{error="serialization"}[5m])

# Transaction retry rate
rate(transaction_retries[5m])

# Lock wait time
histogram_quantile(0.95, rate(lock_wait_duration_seconds_bucket[5m]))

# Concurrent transaction count
sum(active_transactions)
```

### Logging

```
2025-11-16 10:15:23.456 [isolation-demo-004] INFO  IsolationDemoService - Starting SERIALIZABLE transaction
2025-11-16 10:15:23.789 [isolation-demo-005] INFO  IsolationDemoService - Starting SERIALIZABLE transaction
2025-11-16 10:15:26.123 [isolation-demo-004] INFO  IsolationDemoService - Transfer completed successfully
2025-11-16 10:15:26.125 [isolation-demo-005] ERROR IsolationDemoService - Serialization failure: SqlState=40001
2025-11-16 10:15:26.226 [isolation-demo-005] WARN  TransferService - Retry attempt 1/3 after serialization failure
```

## Best Practices

### 1. Use Appropriate Isolation Level

```java
// Default READ COMMITTED for most operations
@Transactional
public void standardOperation() { }

// REPEATABLE READ for reports
@Transactional(isolation = Isolation.REPEATABLE_READ)
public Report generateReport() { }

// SERIALIZABLE only when necessary
@Transactional(isolation = Isolation.SERIALIZABLE)
public void criticalOperation() { }
```

### 2. Implement Retry Logic

```java
@Retryable(
    value = {SerializationException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 100, multiplier = 2)
)
@Transactional(isolation = Isolation.SERIALIZABLE)
public void operationWithRetry() {
    // Will automatically retry on serialization failure
}
```

### 3. Keep Transactions Short

```java
// ❌ Bad: Long transaction holds locks
@Transactional
public void processLargeBatch() {
    for (int i = 0; i < 10000; i++) {
        // Each iteration waits for locks
    }
}

// ✅ Good: Short transactions
public void processLargeBatch() {
    for (int i = 0; i < 10000; i++) {
        processSingleItem(i);  // Each has own short transaction
    }
}

@Transactional
private void processSingleItem(int id) {
    // Quick in and out
}
```

### 4. Order Lock Acquisition

```java
// ❌ Bad: Inconsistent lock order (deadlock risk)
@Transactional
public void transfer1to2() {
    lock(account1);  // Thread A locks 1
    lock(account2);  // Thread A locks 2
}

@Transactional
public void transfer2to1() {
    lock(account2);  // Thread B locks 2
    lock(account1);  // Thread B waits for 1 (deadlock!)
}

// ✅ Good: Consistent lock order
@Transactional
public void transfer(Long fromId, Long toId, BigDecimal amount) {
    Long firstId = Math.min(fromId, toId);
    Long secondId = Math.max(fromId, toId);

    Account first = accountRepository.findByIdWithLock(firstId);
    Account second = accountRepository.findByIdWithLock(secondId);

    // Determine actual from/to after locking
    if (fromId.equals(firstId)) {
        first.debit(amount);
        second.credit(amount);
    } else {
        second.debit(amount);
        first.credit(amount);
    }
}
```

### 5. Monitor Lock Contention

```sql
-- Find blocking queries
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

## Common Pitfalls

### 1. Assuming SERIALIZABLE is Always Better

❌ **Wrong**: Use SERIALIZABLE everywhere for "safety"
```java
@Transactional(isolation = Isolation.SERIALIZABLE)
public List<Account> listAccounts() {
    return accountRepository.findAll();  // Overkill!
}
```

✅ **Correct**: Use appropriate isolation level
```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public List<Account> listAccounts() {
    return accountRepository.findAll();
}
```

### 2. Not Handling Serialization Failures

❌ **Wrong**: Ignore serialization exceptions
```java
@Transactional(isolation = Isolation.SERIALIZABLE)
public void transfer(...) {
    // No retry logic - fails permanently
}
```

✅ **Correct**: Implement retry logic
```java
public void transfer(...) {
    retryTemplate.execute(context -> {
        return executeSerializableTransfer(...);
    });
}
```

### 3. Reading Dirty Data

❌ **Wrong**: Expect uncommitted data to be visible
```java
// Transaction A (uncommitted)
account.setBalance(1000);

// Transaction B
Account account = findAccount();  // Won't see 1000
```

✅ **Correct**: Understand READ COMMITTED behavior
```java
// Transaction A
account.setBalance(1000);
commit();  // Now visible

// Transaction B
Account account = findAccount();  // Sees 1000
```

## Summary

This demonstration shows:

✅ Different isolation levels and their guarantees
✅ Concurrency phenomena (non-repeatable reads, phantom reads)
✅ Spring @Transactional isolation configuration
✅ Serialization failure handling and retry logic
✅ Lock behavior and monitoring
✅ Appropriate isolation level selection

**Key Takeaway**: Isolation prevents transactions from interfering with each other. Choose the isolation level that balances consistency requirements with performance needs. Most applications work well with READ COMMITTED; use higher levels only when necessary.

For more demonstrations:
- [Atomicity](./atomicity.md) - All-or-nothing transactions
- [Consistency](./consistency.md) - Constraint enforcement
- [Durability](./durability.md) - Crash recovery
- [Optimistic Locking](../concurrency/optimistic-locking.md) - Version-based concurrency
- [Pessimistic Locking](../concurrency/pessimistic-locking.md) - Lock-based concurrency
- [Deadlocks](../concurrency/deadlocks.md) - Deadlock detection and prevention

## References

- [PostgreSQL Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)
- [Spring Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- [ANSI SQL Isolation Levels](https://www.microsoft.com/en-us/research/publication/a-critique-of-ansi-sql-isolation-levels/)
- [Serializable Snapshot Isolation](https://wiki.postgresql.org/wiki/SSI)
