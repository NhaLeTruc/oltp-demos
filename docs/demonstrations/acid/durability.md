# ACID Durability Demonstration

**Demonstration**: Committed transaction persistence and crash survival

**User Story**: US1 - ACID Transaction Guarantees (T079)

**Functional Requirements**: FR-004

## Overview

Durability is the "D" in ACID and guarantees that once a transaction commits successfully, its changes are permanent—even if the system crashes immediately after. PostgreSQL ensures durability through Write-Ahead Logging (WAL) and fsync operations.

### What You'll Learn

- PostgreSQL durability guarantees
- Write-Ahead Logging (WAL) mechanism
- fsync and synchronous_commit settings
- Commit semantics and performance trade-offs
- Durability verification techniques
- Recovery after system crash

### Prerequisites

- Application running on `localhost:8080`
- PostgreSQL database running (Docker)
- Understanding of transaction basics
- Ability to simulate crashes (Docker restart)

## The Durability Guarantee

```
┌─────────────────────────────────────────────────┐
│ Application Transaction                         │
│                                                  │
│  BEGIN;                                         │
│    UPDATE accounts SET balance = 1000           │
│      WHERE id = 1;                              │
│  COMMIT; ◄──────────────────────────────────┐  │
│     │                                        │  │
│     │ COMMIT returns success                 │  │
│     │ = Changes are DURABLE                  │  │
│     │                                        │  │
│     └─────────────────┐                      │  │
└───────────────────────┼──────────────────────┼──┘
                        │                      │
                        ▼                      │
              ┌──────────────────┐             │
              │  Write-Ahead Log │             │
              │                  │             │
              │  1. Write changes├─────────────┘
              │     to WAL       │  Changes written
              │  2. fsync WAL    │  to disk BEFORE
              │     to disk      │  COMMIT returns
              │  3. Return       │
              │     success      │
              └────────┬─────────┘
                       │
                       │ Later (async)
                       ▼
              ┌──────────────────┐
              │  Data Files      │
              │                  │
              │  Apply changes   │
              │  from WAL        │
              │  (checkpoint)    │
              └──────────────────┘

Power Loss / Crash:
  - If crash BEFORE COMMIT returns: Changes lost ✓ (not durable yet)
  - If crash AFTER COMMIT returns: Changes preserved ✓ (durable)
```

## Write-Ahead Logging (WAL)

### WAL Principle

**The Golden Rule**: Write changes to WAL **before** modifying data files

1. **Write to WAL**: All changes written to sequential log
2. **Fsync WAL**: Force WAL to disk (physical write)
3. **Return SUCCESS**: COMMIT completes, transaction is durable
4. **Update data files**: Happens later asynchronously

### Why WAL Works

**Without WAL** (direct data file updates):
```
UPDATE accounts SET balance = 1000 WHERE id = 1;
  ↓
Update data file page
  ↓
Crash before disk write completes
  ↓
LOST! (partial write, corruption)
```

**With WAL**:
```
UPDATE accounts SET balance = 1000 WHERE id = 1;
  ↓
Write to WAL (sequential, fast)
  ↓
Fsync WAL to disk
  ↓
COMMIT returns ← DURABLE
  ↓
Crash here? No problem!
  ↓
On restart: Replay WAL → Data recovered
```

## Demonstrations

### 1. Verify Durability After Crash

**Step 1**: Create transaction with known ID

```bash
curl -X POST http://localhost:8080/api/demos/acid/atomicity/transfer \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: durability-test-001' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 250.00
  }'
```

**Response** (commit successful):
```json
{
  "success": true,
  "transactionId": 1001,
  "correlationId": "durability-test-001",
  "fromAccountBalanceBefore": "5000.00",
  "fromAccountBalanceAfter": "4750.00",
  "toAccountBalanceBefore": "3000.00",
  "toAccountBalanceAfter": "3250.00"
}
```

**Step 2**: Immediately crash database

```bash
# Simulate hard crash (SIGKILL)
docker kill -s KILL postgres
```

**Step 3**: Restart database

```bash
docker start postgres
# Wait for recovery (WAL replay)
sleep 5
```

**Step 4**: Verify transaction survived

```bash
curl 'http://localhost:8080/api/demos/failure/recovery/verify?correlationId=durability-test-001'
```

**Response** (transaction found):
```json
{
  "correlationId": "durability-test-001",
  "verified": true,
  "transactionCount": 1,
  "transferLogCount": 1,
  "committedCount": 1,
  "message": "Transaction survived crash - durability verified",
  "transactions": [
    {
      "id": 1001,
      "fromAccountId": 1,
      "toAccountId": 2,
      "amount": 250.00,
      "status": "SUCCESS",
      "correlationId": "durability-test-001"
    }
  ]
}
```

**Verification**:
```bash
# Check actual balances
curl http://localhost:8080/api/accounts/1
# Balance: 4750.00 ✓

curl http://localhost:8080/api/accounts/2
# Balance: 3250.00 ✓
```

**Key Points**:
- ✅ COMMIT returned successfully before crash
- ✅ Database crashed immediately after
- ✅ WAL preserved committed transaction
- ✅ Recovery replayed WAL and restored data
- ✅ Durability guarantee upheld

### 2. Uncommitted Transaction (Not Durable)

**Step 1**: Start transaction but don't commit

```java
// Simulated in application (not via curl)
@Transactional
public void uncommittedTransaction() {
    Account account = accountRepository.findById(1L).orElseThrow();
    account.setBalance(new BigDecimal("9999.00"));
    accountRepository.save(account);

    // Crash before COMMIT
    System.exit(1);  // Simulated crash
}
```

**Step 2**: Verify changes lost after recovery

```bash
curl http://localhost:8080/api/accounts/1
# Balance: 4750.00 (original value, not 9999.00)
```

**Key Points**:
- ✅ COMMIT never returned
- ✅ Changes not written to WAL
- ✅ Recovery rolled back uncommitted transaction
- ✅ Data remains consistent

### 3. Synchronous Commit Verification

**Scenario**: Verify WAL flushed before COMMIT returns

```bash
curl -X POST http://localhost:8080/api/demos/acid/durability/sync-commit \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: durability-test-002' \
  -d '{
    "accountId": 1,
    "amount": 100.00,
    "synchronousCommit": true
  }'
```

**Response**:
```json
{
  "success": true,
  "correlationId": "durability-test-002",
  "synchronousCommit": true,
  "walFlushed": true,
  "walLsnBefore": "0/1A2B3C4D",
  "walLsnAfter": "0/1A2B5678",
  "commitLatency": 15,
  "message": "WAL flushed to disk before COMMIT returned (durable)"
}
```

**Key Fields**:
- `synchronousCommit`: true = fsync before COMMIT
- `walFlushed`: Confirms WAL written to disk
- `walLsnBefore/After`: WAL position before/after transaction
- `commitLatency`: Time for fsync (ms)

### 4. Asynchronous Commit (Performance vs Durability)

```bash
curl -X POST http://localhost:8080/api/demos/acid/durability/async-commit \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: durability-test-003' \
  -d '{
    "accountId": 1,
    "amount": 100.00,
    "synchronousCommit": false
  }'
```

**Response**:
```json
{
  "success": true,
  "correlationId": "durability-test-003",
  "synchronousCommit": false,
  "walFlushed": false,
  "commitLatency": 2,
  "message": "COMMIT returned before WAL flushed (async mode)",
  "warning": "Recent transactions may be lost if crash occurs immediately"
}
```

**Trade-off**:
- ⚡ **Faster**: 2ms vs 15ms commit latency
- ⚠️ **Risk**: Last few transactions may be lost on crash
- ✅ **Use case**: Non-critical data, high throughput

## Durability Settings

### PostgreSQL Configuration

**View current settings**:
```bash
curl http://localhost:8080/api/demos/acid/durability/settings
```

**Response**:
```json
{
  "fsync": "on",
  "synchronous_commit": "on",
  "wal_sync_method": "fsync",
  "wal_level": "replica",
  "wal_buffers": "16MB",
  "checkpoint_timeout": "5min",
  "max_wal_size": "1GB",
  "full_page_writes": "on"
}
```

### Key Settings Explained

#### fsync

```sql
-- Default (safe)
fsync = on
-- Ensures WAL written to disk before COMMIT

-- Dangerous (never use in production)
fsync = off
-- WAL not guaranteed to reach disk
-- Risk of data loss on crash
```

**When to disable**: Never in production. Only for testing/development.

#### synchronous_commit

```sql
-- Synchronous (default, durable)
synchronous_commit = on
-- COMMIT waits for WAL flush
-- Slower, but guaranteed durability

-- Asynchronous (fast, risky)
synchronous_commit = off
-- COMMIT returns before WAL flush
-- Faster, but last ~1s of transactions may be lost on crash

-- Local only
synchronous_commit = local
-- Flush to disk, don't wait for replicas
```

**Performance Impact**:
```
synchronous_commit = on:  ~10-20ms per COMMIT
synchronous_commit = off: ~1-2ms per COMMIT
```

**Trade-off**:
- `on`: Guaranteed durability, slower
- `off`: Faster, possible data loss (last few transactions)

#### wal_sync_method

```sql
-- Default (most compatible)
wal_sync_method = fsync

-- Alternative methods (platform-specific)
-- fdatasync    (Linux, like fsync but doesn't update metadata)
-- open_sync    (Write with O_SYNC flag)
-- open_datasync (Write with O_DSYNC flag)
```

**Performance varies by OS and hardware**: Test to find fastest on your system.

#### full_page_writes

```sql
-- Default (safe)
full_page_writes = on
-- Write entire page on first modification after checkpoint
-- Protects against partial page writes

-- Risky (only with reliable storage)
full_page_writes = off
-- Better performance, but risk of corruption on crash
```

## Durability Verification

### 1. Check WAL Status

```bash
curl http://localhost:8080/api/demos/failure/recovery/wal
```

**Response**:
```json
{
  "walEnabled": true,
  "currentWalLsn": "0/1A2B3C4D",
  "lastCheckpointLsn": "0/1A2B0000",
  "walSettings": {
    "wal_level": "replica",
    "wal_sync_method": "fsync"
  },
  "message": "WAL is enabled and operational"
}
```

### 2. Verify Transaction in WAL

```sql
-- Check transaction in pg_waldump (requires shell access)
docker exec postgres pg_waldump \
  -p /var/lib/postgresql/data/pg_wal \
  -s 0/1A2B3C4D \
  | grep "COMMIT"
```

**Output**:
```
rmgr: Transaction len: 34  tx: 1001  desc: COMMIT 2025-11-16 10:15:23.456789 UTC
```

### 3. Monitor Checkpoint Activity

```bash
curl http://localhost:8080/api/demos/failure/recovery/statistics
```

**Response**:
```json
{
  "checkpointStats": {
    "timedCheckpoints": 12,
    "requestedCheckpoints": 2,
    "writeTime": 1523.45,
    "syncTime": 234.12,
    "buffersWritten": 42351
  }
}
```

## Performance vs Durability

### Scenario Comparison

| Scenario                     | synchronous_commit | Commit Latency | Durability | Use Case |
|------------------------------|-------------------|----------------|------------|----------|
| Critical financial txn       | on                | 10-20ms        | 100%       | Banking  |
| User session data            | off               | 1-2ms          | ~99.9%     | Sessions |
| Analytics events             | off               | 1-2ms          | ~99.9%     | Logs     |
| Replicated database (master) | remote_apply      | 50-100ms       | 100%+      | HA setup |

### Benchmarking

**Test synchronous commit**:
```bash
# Run benchmark with synchronous_commit=on
curl -X POST http://localhost:8080/api/demos/performance/benchmark \
  -H 'Content-Type: application/json' \
  -d '{
    "duration": 60,
    "threadsPerPool": 10,
    "synchronousCommit": true
  }'
```

**Response**:
```json
{
  "throughput": 850,
  "avgLatency": 11.7,
  "p99Latency": 24.3
}
```

**Test asynchronous commit**:
```bash
curl -X POST http://localhost:8080/api/demos/performance/benchmark \
  -d '{
    "duration": 60,
    "threadsPerPool": 10,
    "synchronousCommit": false
  }'
```

**Response**:
```json
{
  "throughput": 4250,
  "avgLatency": 2.3,
  "p99Latency": 5.1
}
```

**Trade-off**: 5x higher throughput, but risk of losing last ~1 second of transactions on crash.

## Application-Level Considerations

### Setting synchronous_commit Per Transaction

```java
@Service
public class TransferService {

    // Critical transactions (default synchronous)
    @Transactional
    public TransferResult criticalTransfer(
        Long fromId,
        Long toId,
        BigDecimal amount
    ) {
        // synchronous_commit = on (default)
        // Guaranteed durable
        return executeTransfer(fromId, toId, amount);
    }

    // Non-critical transactions (async for speed)
    @Transactional
    public void logAnalyticsEvent(AnalyticsEvent event) {
        // Override for this transaction only
        entityManager.createNativeQuery(
            "SET LOCAL synchronous_commit = off"
        ).executeUpdate();

        analyticsRepository.save(event);
        // Faster commit, slight durability risk
    }
}
```

### Verifying Commit Completion

```java
@Service
public class DurabilityService {

    @Transactional
    public TransferResult transferWithVerification(
        Long fromId,
        Long toId,
        BigDecimal amount,
        String correlationId
    ) {
        // Execute transfer
        TransferResult result = executeTransfer(fromId, toId, amount);

        // Ensure changes flushed to database
        entityManager.flush();

        // Optional: Force WAL flush (for critical operations)
        entityManager.createNativeQuery("SELECT pg_current_wal_flush_lsn()")
            .getSingleResult();

        // COMMIT will happen here
        return result;
    }
}
```

### Handling Durability Failures

```java
@Service
public class RobustTransferService {

    public TransferResult transferWithRetry(
        Long fromId,
        Long toId,
        BigDecimal amount
    ) {
        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            try {
                TransferResult result = executeTransfer(fromId, toId, amount);

                // Verify transaction is durable
                verifyCommitted(result.getCorrelationId());

                return result;

            } catch (PSQLException e) {
                if (e.getSQLState().equals("57P01")) {  // Admin shutdown
                    attempt++;
                    log.warn("Database shutting down, retry {}/{}",
                        attempt, maxRetries);
                    Thread.sleep(1000);
                } else {
                    throw e;
                }
            }
        }

        throw new DurabilityException("Failed to commit after " + maxRetries + " attempts");
    }

    @Transactional
    private TransferResult executeTransfer(...) {
        // Transfer logic
    }

    private void verifyCommitted(String correlationId) {
        // Query to ensure transaction visible
        // (committed and durable)
    }
}
```

## Monitoring Durability

### Metrics

```promql
# WAL write rate
rate(postgresql_wal_write_bytes[5m])

# Checkpoint frequency
rate(postgresql_checkpoints_timed[1h])

# fsync latency
histogram_quantile(0.95, rate(postgresql_fsync_duration_seconds_bucket[5m]))

# Commit latency
histogram_quantile(0.95, rate(commit_duration_seconds_bucket[5m]))
```

### Alerts

```yaml
# WAL not advancing (potential issue)
- alert: WALNotAdvancing
  expr: rate(postgresql_wal_write_bytes[5m]) == 0
  for: 10m

# High fsync latency (disk issue)
- alert: HighFsyncLatency
  expr: histogram_quantile(0.95, rate(postgresql_fsync_duration_seconds_bucket[5m])) > 0.1
  for: 5m

# Too many checkpoints (tune settings)
- alert: FrequentCheckpoints
  expr: rate(postgresql_checkpoints_timed[1h]) > 15
  for: 1h
```

## Best Practices

### 1. Always Use fsync in Production

```sql
-- ✅ Production
fsync = on
synchronous_commit = on

-- ❌ Never in production
fsync = off  -- DATA LOSS RISK
```

### 2. Use Asynchronous Commit Selectively

```java
// ✅ Good: Async for non-critical data
@Transactional
public void logPageView(PageView view) {
    entityManager.createNativeQuery("SET LOCAL synchronous_commit = off")
        .executeUpdate();
    pageViewRepository.save(view);
}

// ❌ Bad: Async for financial transactions
@Transactional
public void transferMoney(...) {
    entityManager.createNativeQuery("SET LOCAL synchronous_commit = off")
        .executeUpdate();
    // Money transfer at risk!
}
```

### 3. Monitor WAL and Checkpoints

- Track WAL growth rate
- Tune `checkpoint_timeout` and `max_wal_size`
- Monitor fsync latency (disk health indicator)

### 4. Test Recovery Procedures

**Monthly drill**:
1. Perform test transactions with known IDs
2. Crash database (`docker kill -s KILL postgres`)
3. Restart and verify recovery
4. Confirm all committed transactions survived

### 5. Understand Commit Latency

**Factors affecting commit speed**:
- Disk I/O performance (SSD vs HDD)
- `synchronous_commit` setting
- `wal_sync_method` (varies by OS)
- Concurrent transaction volume
- Checkpoint activity

## Common Pitfalls

### 1. Assuming COMMIT = Durable

❌ **Wrong**:
```java
@Transactional
public void transfer(...) {
    // Execute transfer
    // COMMIT happens here
}
// Assumption: Data is durable
// Reality: Only if synchronous_commit = on
```

✅ **Correct**:
```java
// Verify synchronous_commit setting
String syncCommit = (String) entityManager
    .createNativeQuery("SHOW synchronous_commit")
    .getSingleResult();

if (!"on".equals(syncCommit)) {
    throw new IllegalStateException(
        "Durability requires synchronous_commit=on"
    );
}
```

### 2. Disabling fsync for "Performance"

❌ **Wrong**:
```sql
-- "Optimization"
ALTER SYSTEM SET fsync = off;
-- 💥 DATA LOSS ON CRASH
```

✅ **Correct**:
```sql
-- Tune checkpoint settings instead
ALTER SYSTEM SET checkpoint_timeout = '10min';
ALTER SYSTEM SET max_wal_size = '2GB';
-- Faster with durability intact
```

### 3. Not Testing Recovery

❌ **Wrong**: Assume durability works without testing

✅ **Correct**: Regular recovery drills
```bash
# Automated test
./test-durability.sh
  1. Create test transactions
  2. Kill database
  3. Restart
  4. Verify recovery
  5. Report results
```

## Summary

This demonstration shows:

✅ Durability guarantees committed transactions persist
✅ Write-Ahead Logging (WAL) ensures crash recovery
✅ fsync and synchronous_commit control durability
✅ Performance vs durability trade-offs
✅ Verification techniques for committed transactions
✅ Application-level durability handling

**Key Takeaways**:
- Durability means committed transactions survive crashes
- WAL is the mechanism: write log before data files
- fsync must be `on` in production
- synchronous_commit trades performance for guarantees
- Always test recovery procedures regularly

For more demonstrations:
- [Atomicity](./atomicity.md) - All-or-nothing transactions
- [Consistency](./consistency.md) - Constraint enforcement
- [Isolation](./isolation.md) - Concurrent transaction handling
- [Crash Recovery](../failure/recovery.md) - Detailed WAL and recovery process
- [Failure Handling](../failure/failure-handling.md) - Resilience patterns

## References

- [PostgreSQL Write-Ahead Logging](https://www.postgresql.org/docs/current/wal-intro.html)
- [PostgreSQL Durability Settings](https://www.postgresql.org/docs/current/wal-configuration.html)
- [Reliability and Write-Ahead Logging](https://www.postgresql.org/docs/current/wal-reliability.html)
- [Asynchronous Commit](https://www.postgresql.org/docs/current/wal-async-commit.html)
