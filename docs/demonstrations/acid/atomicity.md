# ACID Atomicity Demonstration

**Demonstration**: All-or-nothing transaction execution

**User Story**: US1 - ACID Transaction Guarantees (T076)

**Functional Requirements**: FR-001

## Overview

Atomicity is the "A" in ACID and guarantees that a transaction either completes fully or has no effect at all. Partial updates are never visible to other transactions or persisted to the database.

### What You'll Learn

- Spring `@Transactional` annotation for transaction management
- Automatic rollback on exceptions
- Manual rollback with `TransactionStatus`
- Partial update prevention
- Transaction boundaries and commit points

### Prerequisites

- Application running on `localhost:8080`
- PostgreSQL database initialized
- Test accounts created (via seed data or setup scripts)

## The Atomicity Guarantee

```
┌─────────────────────────────────────────┐
│ Transfer $100 from Account A to B      │
│                                         │
│  BEGIN TRANSACTION;                    │
│    1. Debit $100 from Account A        │ ◄── Step 1
│    2. Credit $100 to Account B         │ ◄── Step 2
│  COMMIT;                               │
│                                         │
│  Outcomes:                             │
│  ✓ Both steps succeed → COMMIT         │
│  ✗ Either step fails → ROLLBACK ALL    │
└─────────────────────────────────────────┘

Atomicity means NO partial state:
- ✗ Account A debited but B not credited
- ✗ Account B credited but A not debited
- ✓ Both succeed or neither happens
```

## Demonstrations

### 1. Successful Atomic Transfer

**Scenario**: Transfer succeeds when both accounts have sufficient funds

```bash
# Execute successful transfer
curl -X POST http://localhost:8080/api/demos/acid/atomicity/transfer \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: atomicity-demo-001' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00
  }'
```

**Response** (successful):
```json
{
  "success": true,
  "transactionId": 42,
  "fromAccountId": 1,
  "toAccountId": 2,
  "amount": 100.00,
  "fromAccountBalanceBefore": "1000.00",
  "fromAccountBalanceAfter": "900.00",
  "toAccountBalanceBefore": "500.00",
  "toAccountBalanceAfter": "600.00",
  "duration": 25,
  "correlationId": "atomicity-demo-001"
}
```

**Verification**:
```bash
# Verify both accounts updated atomically
# Account 1 balance should be 900.00
# Account 2 balance should be 600.00
curl http://localhost:8080/api/accounts/1
curl http://localhost:8080/api/accounts/2
```

**Key Points**:
- ✅ Both debit and credit executed
- ✅ Balances updated atomically
- ✅ Transaction committed successfully
- ✅ No intermediate state visible

### 2. Rollback on Insufficient Funds

**Scenario**: Transfer fails when source account has insufficient funds, entire transaction rolls back

```bash
# Attempt transfer exceeding balance
curl -X POST http://localhost:8080/api/demos/acid/atomicity/transfer \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: atomicity-demo-002' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 50000.00
  }'
```

**Response** (failure with rollback):
```json
{
  "success": false,
  "error": "InsufficientFundsException",
  "message": "Insufficient funds in account 1: balance=900.00, required=50000.00",
  "correlationId": "atomicity-demo-002",
  "rollback": true
}
```

**Verification**:
```bash
# Verify NO changes occurred
# Account 1 balance should still be 900.00
# Account 2 balance should still be 600.00
curl http://localhost:8080/api/accounts/1
curl http://localhost:8080/api/accounts/2
```

**Key Points**:
- ✅ Validation failed before debit
- ✅ No changes persisted (rollback)
- ✅ Account balances unchanged
- ✅ Atomicity preserved

### 3. Mid-Transaction Failure

**Scenario**: Simulate a failure after first operation but before commit

```bash
# Simulate mid-transaction crash
curl -X POST http://localhost:8080/api/demos/acid/atomicity/fail-mid-transaction \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: atomicity-demo-003' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00,
    "failAfterDebit": true
  }'
```

**Response** (simulated failure):
```json
{
  "success": false,
  "error": "SimulatedTransactionException",
  "message": "Simulated failure after debit operation",
  "correlationId": "atomicity-demo-003",
  "rollback": true,
  "partialChanges": "Debit executed but rolled back"
}
```

**Verification**:
```bash
# Verify rollback occurred
# Account 1 balance should still be 900.00 (debit rolled back)
# Account 2 balance should still be 600.00 (credit never executed)
curl http://localhost:8080/api/accounts/1
curl http://localhost:8080/api/accounts/2
```

**Key Points**:
- ✅ Debit executed in memory
- ✅ Exception thrown before credit
- ✅ Spring automatically rolled back entire transaction
- ✅ No partial state persisted

## Implementation Details

### Spring @Transactional Annotation

```java
@Transactional
public TransferResult successfulTransfer(
    Long fromAccountId,
    Long toAccountId,
    BigDecimal amount
) {
    // Spring creates transaction boundary
    Account from = accountRepository.findById(fromAccountId).orElseThrow();
    Account to = accountRepository.findById(toAccountId).orElseThrow();

    // Validate (throws exception if insufficient funds)
    if (from.getBalance().compareTo(amount) < 0) {
        throw new InsufficientFundsException(...);
    }

    // Execute operations within transaction
    from.debit(amount);      // Step 1
    to.credit(amount);       // Step 2

    accountRepository.save(from);
    accountRepository.save(to);

    // If we reach here, Spring commits on method return
    return TransferResult.success(...);
}
// Transaction commits here if no exception
// Transaction rolls back if any exception thrown
```

### Automatic Rollback Rules

**Default Behavior**:
- `RuntimeException` → **Automatic Rollback**
- `Error` → **Automatic Rollback**
- `Checked Exception` → **No Rollback** (must configure explicitly)

**Custom Rollback Rules**:
```java
@Transactional(
    rollbackFor = {Exception.class},  // Rollback on checked exceptions
    noRollbackFor = {IgnoredException.class}  // Don't rollback for specific exception
)
```

### Transaction Boundaries

```
HTTP Request Start
    │
    ▼
@Transactional method invoked
    │
    ├── BEGIN TRANSACTION
    │
    ├── Execute business logic
    │   ├── Debit operation
    │   ├── Credit operation
    │   └── Validation
    │
    ├── Exception occurred?
    │   ├─ Yes → ROLLBACK
    │   └─ No  → COMMIT
    │
@Transactional method returns
    │
    ▼
HTTP Response
```

## Application Logs

**Successful transfer**:
```
2025-11-16 10:15:23.456 [atomicity-demo-001] INFO  AtomicityDemoService - Starting atomic transfer: from=1, to=2, amount=100.00
2025-11-16 10:15:23.458 [atomicity-demo-001] DEBUG AccountRepository - Finding account by ID: 1
2025-11-16 10:15:23.460 [atomicity-demo-001] DEBUG AccountRepository - Finding account by ID: 2
2025-11-16 10:15:23.462 [atomicity-demo-001] DEBUG Account - Debiting 100.00 from account 1, new balance: 900.00
2025-11-16 10:15:23.463 [atomicity-demo-001] DEBUG Account - Crediting 100.00 to account 2, new balance: 600.00
2025-11-16 10:15:23.480 [atomicity-demo-001] INFO  AtomicityDemoService - Transfer completed successfully: txnId=42, duration=24ms
```

**Failed transfer (rollback)**:
```
2025-11-16 10:16:45.123 [atomicity-demo-002] INFO  AtomicityDemoService - Starting atomic transfer: from=1, to=2, amount=50000.00
2025-11-16 10:16:45.125 [atomicity-demo-002] DEBUG AccountRepository - Finding account by ID: 1
2025-11-16 10:16:45.127 [atomicity-demo-002] WARN  AtomicityDemoService - Insufficient funds: account=1, balance=900.00, required=50000.00
2025-11-16 10:16:45.128 [atomicity-demo-002] INFO  AtomicityDemoService - Transfer rolled back: reason=InsufficientFundsException
```

## Database Queries

**What happens in PostgreSQL**:

```sql
-- Spring starts transaction
BEGIN;

-- Find and lock accounts
SELECT * FROM accounts WHERE id = 1;
SELECT * FROM accounts WHERE id = 2;

-- Update balances
UPDATE accounts SET balance = balance - 100.00 WHERE id = 1;
UPDATE accounts SET balance = balance + 100.00 WHERE id = 2;

-- Insert transaction record
INSERT INTO transactions (from_account_id, to_account_id, amount, status)
VALUES (1, 2, 100.00, 'SUCCESS');

-- If success:
COMMIT;

-- If failure:
ROLLBACK;
```

## Testing Atomicity

### Concurrent Transfer Test

```bash
# Start two transfers simultaneously
# Both should succeed atomically or fail atomically

# Terminal 1:
curl -X POST http://localhost:8080/api/demos/acid/atomicity/transfer \
  -H 'Content-Type: application/json' \
  -d '{"fromAccountId": 1, "toAccountId": 2, "amount": 100.00}' &

# Terminal 2:
curl -X POST http://localhost:8080/api/demos/acid/atomicity/transfer \
  -H 'Content-Type: application/json' \
  -d '{"fromAccountId": 1, "toAccountId": 2, "amount": 100.00}' &

# Verify final balance
curl http://localhost:8080/api/accounts/1
# Expected: balance reduced by exactly 200.00 (both transfers succeed)
# OR: balance reduced by 100.00 (one succeeds, one fails)
# NEVER: balance in inconsistent state
```

### Metrics

**Monitor atomicity metrics**:
```promql
# Transfer success rate
sum(rate(transfer_count{status="success"}[5m])) /
sum(rate(transfer_count[5m])) * 100

# Rollback rate
rate(transfer_count{status="rolled_back"}[5m])

# Average transaction duration
rate(transfer_duration_sum[5m]) / rate(transfer_duration_count[5m])
```

## Common Pitfalls

### 1. Missing @Transactional Annotation

❌ **Wrong**:
```java
// No transaction boundary - changes auto-committed
public void transfer(Long from, Long to, BigDecimal amount) {
    Account a = accountRepository.findById(from).orElseThrow();
    a.debit(amount);
    accountRepository.save(a);  // Committed here

    // If exception here, debit is NOT rolled back!
    Account b = accountRepository.findById(to).orElseThrow();
    b.credit(amount);
    accountRepository.save(b);
}
```

✅ **Correct**:
```java
@Transactional  // Transaction boundary
public void transfer(Long from, Long to, BigDecimal amount) {
    // All or nothing
}
```

### 2. Catching and Swallowing Exceptions

❌ **Wrong**:
```java
@Transactional
public void transfer(...) {
    try {
        // operations
    } catch (Exception e) {
        log.error("Error", e);
        // Exception swallowed - Spring won't rollback!
    }
}
```

✅ **Correct**:
```java
@Transactional
public void transfer(...) {
    try {
        // operations
    } catch (Exception e) {
        log.error("Error", e);
        throw e;  // Re-throw to trigger rollback
    }
}
```

### 3. Transaction Scope Too Large

❌ **Wrong**:
```java
@Transactional
public void processLargeBatch() {
    // Keep transaction open for minutes
    for (int i = 0; i < 1000000; i++) {
        // Long-running operation
    }
}  // Locks held too long, poor performance
```

✅ **Correct**:
```java
public void processLargeBatch() {
    for (int i = 0; i < 1000000; i++) {
        processSingleItem(i);  // Each has own transaction
    }
}

@Transactional
private void processSingleItem(int id) {
    // Short transaction, releases locks quickly
}
```

## Best Practices

1. **Keep transactions short**: Long transactions hold locks and reduce concurrency
2. **Validate before transaction**: Check business rules early to avoid unnecessary rollbacks
3. **Use @Transactional consistently**: Don't mix transactional and non-transactional code
4. **Let exceptions propagate**: Spring needs to see exceptions to rollback
5. **Test rollback scenarios**: Verify partial updates don't persist

## Summary

This demonstration shows:

✅ Atomic transfer execution (all-or-nothing)
✅ Automatic rollback on exceptions
✅ Prevention of partial updates
✅ Transaction boundary management with @Transactional
✅ Proper error handling and rollback

**Key Takeaway**: Atomicity ensures that either all operations in a transaction succeed, or none of them do. There is no middle ground or partial state.

For more demonstrations:
- [Consistency](./consistency.md) - Constraint enforcement
- [Isolation](./isolation.md) - Concurrent transaction handling
- [Durability](./durability.md) - Crash recovery

## References

- [Spring Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html)
- [PostgreSQL Transaction Isolation](https://www.postgresql.org/docs/current/tutorial-transactions.html)
- [ACID Properties](https://en.wikipedia.org/wiki/ACID)
