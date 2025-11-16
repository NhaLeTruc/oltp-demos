# Crash Recovery and Durability Verification

**Demonstration**: Database crash recovery, WAL verification, and durability testing

**User Story**: US5 - Failure Scenarios and Recovery (T197)

**Functional Requirements**: FR-022

## Overview

This demonstration shows how PostgreSQL ensures ACID Durability through Write-Ahead Logging (WAL), crash recovery, and how to verify that committed transactions survive database crashes and restarts.

### What You'll Learn

- PostgreSQL Write-Ahead Logging (WAL) mechanism
- Crash recovery process and WAL replay
- Committed transaction verification after crash
- Point-in-time recovery queries
- Recovery statistics and monitoring
- Durability verification techniques

### Prerequisites

- Application running on `localhost:8080`
- PostgreSQL database running (Docker)
- Basic understanding of ACID properties
- Familiarity with database transactions

## Architecture

```
┌─────────────────────────────────────────────────────┐
│ Application Transaction                             │
│                                                      │
│  BEGIN;                                             │
│    UPDATE accounts SET balance = balance - 100     │
│      WHERE id = 1;                                  │
│    UPDATE accounts SET balance = balance + 100     │
│      WHERE id = 2;                                  │
│  COMMIT;                                            │
└────────────────┬────────────────────────────────────┘
                 │
                 ▼
      ┌──────────────────────┐
      │  PostgreSQL          │
      │  Write-Ahead Log     │
      │                      │
      │  1. Write to WAL     │◄─── Durability
      │  2. Flush WAL to disk│     Guarantee
      │  3. COMMIT returns   │
      │  4. Apply to data    │     (async)
      │     files later      │
      └──────────┬───────────┘
                 │
                 ▼
    ┌────────────────────────┐
    │  pg_wal/ directory     │
    │                        │
    │  - WAL files (16MB)    │
    │  - Checkpoint records  │
    │  - Transaction log     │
    └────────────────────────┘

    After Crash:
    ┌────────────────────────┐
    │  Recovery Process      │
    │                        │
    │  1. Read last checkpoint
    │  2. Replay WAL from    │
    │     checkpoint → crash │
    │  3. REDO committed txns│
    │  4. UNDO uncommitted   │
    │  5. Database ready     │
    └────────────────────────┘
```

## Write-Ahead Logging (WAL)

### WAL Principles

**Write-Ahead Logging** ensures durability by:
1. Writing all changes to WAL **before** modifying data files
2. Flushing WAL to disk **before** COMMIT returns
3. Applying changes to data files asynchronously (lazily)
4. Replaying WAL after crash to recover committed transactions

**Key Guarantee**: If COMMIT returns successfully, the transaction is durable even if the server crashes immediately after.

### WAL Verification

**Check WAL configuration**:
```bash
curl http://localhost:8080/api/demos/failure/recovery/wal
```

**Response**:
```json
{
  "correlationId": "abc-123",
  "walEnabled": true,
  "currentWalLsn": "0/1A2B3C4D",
  "lastCheckpointLsn": "0/1A2B0000",
  "inRecovery": false,
  "walInfo": {
    "walSettings": {
      "wal_level": "replica",
      "wal_sync_method": "fsync",
      "wal_buffers": "16MB",
      "checkpoint_timeout": "5min",
      "max_wal_size": "1GB"
    }
  },
  "message": "WAL is enabled and operational"
}
```

**Key Fields**:
- `walEnabled`: Whether WAL is active (should always be true)
- `currentWalLsn`: Current WAL Log Sequence Number (position)
- `lastCheckpointLsn`: Last checkpoint position
- `inRecovery`: Whether database is currently in recovery mode
- `walSettings`: PostgreSQL WAL configuration

### WAL Configuration Explained

**wal_level**:
- `minimal`: Only crash recovery (no replication)
- `replica`: Enables streaming replication (default)
- `logical`: Enables logical decoding for CDC

**wal_sync_method**:
- `fsync`: Safest, ensures WAL written to disk (default)
- `fdatasync`: Similar to fsync
- `open_sync`, `open_datasync`: Alternative sync methods

**checkpoint_timeout**:
- How often checkpoints occur
- Trade-off: Frequent checkpoints = slower performance, faster recovery
- Default: 5 minutes

**max_wal_size**:
- Maximum WAL size before forcing checkpoint
- Prevents unlimited WAL growth
- Default: 1GB

## Crash Recovery Demonstration

### Step 1: Create Committed Transactions

```bash
# Perform transfer with correlation ID for tracking
curl -X POST http://localhost:8080/api/demos/acid/atomicity/transfer \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: recovery-test-001' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 500.00
  }'
```

**Response (successful)**:
```json
{
  "success": true,
  "transactionId": 101,
  "fromAccountBalanceBefore": "10000.00",
  "fromAccountBalanceAfter": "9500.00",
  "toAccountBalanceBefore": "5000.00",
  "toAccountBalanceAfter": "5500.00",
  "correlationId": "recovery-test-001"
}
```

### Step 2: Simulate Database Crash

```bash
# Hard kill (simulates power loss / SIGKILL)
./infrastructure/scripts/chaos/kill-db.sh --hard-kill

# Output:
# Database stopped
# Database is not responding (expected)
# Restarting database (simulating recovery)...
# Database recovery complete (WAL replay finished)
```

**What happens**:
1. Database stops immediately (simulating crash)
2. Uncommitted transactions are lost
3. WAL files remain on disk
4. On restart, PostgreSQL replays WAL
5. Committed transactions are recovered

### Step 3: Verify Transaction Survived Crash

```bash
# Verify transaction with correlation ID
curl 'http://localhost:8080/api/demos/failure/recovery/verify?correlationId=recovery-test-001'
```

**Response (transaction found after crash)**:
```json
{
  "correlationId": "recovery-test-001",
  "verified": true,
  "transactionCount": 1,
  "transferLogCount": 1,
  "committedCount": 1,
  "message": "Found 1 transactions, 1 committed, 1 audit logs",
  "transactions": [
    {
      "id": 101,
      "fromAccountId": 1,
      "toAccountId": 2,
      "amount": 500.00,
      "status": "SUCCESS",
      "correlationId": "recovery-test-001"
    }
  ]
}
```

**Verification confirms**:
- ✅ Transaction survived database crash
- ✅ ACID Durability property verified
- ✅ WAL replay recovered committed transaction
- ✅ Audit logs also survived

### Step 4: Verify Account Balances

```bash
# Check account balances
curl http://localhost:8080/api/accounts/1
curl http://localhost:8080/api/accounts/2
```

**Verification**:
- Account 1 balance: 9500.00 (decreased by 500)
- Account 2 balance: 5500.00 (increased by 500)
- Balances match pre-crash state

## Recovery Process Explained

### Crash Recovery Steps

When PostgreSQL starts after a crash:

1. **Read Last Checkpoint**
   - Location stored in `pg_control` file
   - Determines starting point for recovery

2. **Scan WAL Files**
   - From checkpoint to end of WAL
   - Identifies all committed and uncommitted transactions

3. **REDO Phase**
   - Replay all committed transactions
   - Apply changes from WAL to data files
   - Ensures all committed work is present

4. **UNDO Phase**
   - Roll back uncommitted transactions
   - Clean up partial changes
   - Ensures consistency

5. **Database Ready**
   - Accepts new connections
   - Normal operation resumes

### Timeline

```
Time →

Before Crash:
[Checkpoint]──[Txn1 COMMIT]──[Txn2 COMMIT]──[Txn3 START]──[CRASH!]
     ▲              ✓              ✓             ✗
     │
     └─ Recovery starts here

After Restart:
[Read Checkpoint]──[Replay WAL]──[REDO Txn1]──[REDO Txn2]──[UNDO Txn3]──[Ready]
                                      ✓            ✓            ✗
                                 Recovered    Recovered    Rolled Back
```

### Uncommitted Transactions

**Scenario**: Transaction starts but crashes before COMMIT

```sql
BEGIN;
  UPDATE accounts SET balance = balance - 100 WHERE id = 1;
  -- CRASH HERE (before COMMIT)
```

**After recovery**:
- Change is **not** applied (rolled back)
- Account 1 balance unchanged
- No partial updates visible
- Atomicity preserved

## Point-in-Time Recovery

### Overview

Point-in-time recovery allows you to:
- Query transactions within a time range
- Identify what happened before a crash
- Plan recovery strategies
- Audit transaction history

### Query Transactions in Time Range

```bash
# Query transactions from last hour
START_TIME=$(date -u -d '1 hour ago' +"%Y-%m-%dT%H:%M:%SZ")
END_TIME=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

curl "http://localhost:8080/api/demos/failure/recovery/point-in-time?startTime=${START_TIME}&endTime=${END_TIME}"
```

**Response**:
```json
{
  "correlationId": "abc-123",
  "startTime": "2025-11-16T10:00:00Z",
  "endTime": "2025-11-16T11:00:00Z",
  "transactionCount": 42,
  "successCount": 40,
  "totalAmount": "15750.00",
  "transactions": [
    {
      "id": 98,
      "fromAccountId": 1,
      "toAccountId": 2,
      "amount": "100.00",
      "status": "SUCCESS",
      "createdAt": "2025-11-16T10:15:23"
    },
    // ... more transactions
  ],
  "message": "Found 42 transactions in time range"
}
```

### Use Cases

**1. Identify problematic transactions**:
```bash
# Query time range before crash
curl "...&startTime=2025-11-16T10:55:00Z&endTime=2025-11-16T11:00:00Z"
```

**2. Calculate business impact**:
- Total transaction volume before crash
- Number of successful vs failed transactions
- Monetary value at risk

**3. Recovery planning**:
- Which transactions need re-execution
- Which clients need notification
- What data may be inconsistent

## Recovery Statistics

### Database Uptime and Statistics

```bash
curl http://localhost:8080/api/demos/failure/recovery/statistics
```

**Response**:
```json
{
  "databaseStartTime": "2025-11-16T11:00:00Z",
  "uptimeSeconds": 3600,
  "transactionStats": {
    "committed": 15420,
    "rolledBack": 23
  },
  "checkpointStats": {
    "timedCheckpoints": 12,
    "requestedCheckpoints": 2,
    "writeTime": 1523.45,
    "syncTime": 234.12
  }
}
```

**Key Metrics**:
- `uptimeSeconds`: Time since last database start (crash indicator)
- `committed` vs `rolledBack`: Transaction success rate
- `timedCheckpoints`: Regular checkpoints (every 5 min)
- `requestedCheckpoints`: Forced checkpoints (e.g., max_wal_size reached)

**Monitoring**:
```promql
# Database uptime (low value = recent restart)
postgresql_uptime_seconds < 600  # Alert if uptime < 10 minutes

# Rollback rate
rate(postgresql_transactions_rolledback[5m])

# Checkpoint frequency
rate(postgresql_checkpoints_timed[1h])
```

## Testing Scenarios

### Scenario 1: Committed Transaction Survives

1. Start transaction with correlation ID
2. Commit transaction (COMMIT returns success)
3. Kill database immediately after
4. Restart database
5. Verify transaction exists

**Expected**: Transaction found ✓

### Scenario 2: Uncommitted Transaction Rolled Back

1. Start transaction
2. Execute UPDATE statements
3. Kill database before COMMIT
4. Restart database
5. Verify changes were not applied

**Expected**: Changes not visible ✓

### Scenario 3: Multiple Transactions

1. Commit transactions T1, T2, T3
2. Start transaction T4 (don't commit)
3. Crash database
4. Restart and verify

**Expected**:
- T1, T2, T3 found ✓
- T4 not found (rolled back) ✓

### Scenario 4: WAL Replay Performance

1. Generate high transaction volume (1000+ TPS)
2. Crash database
3. Measure recovery time

**Expected**:
- Recovery time proportional to WAL size
- Checkpoint frequency affects recovery speed
- All committed transactions recovered

## Best Practices

### 1. Monitor WAL Growth

```bash
# Check WAL directory size
docker exec postgres du -sh /var/lib/postgresql/data/pg_wal
```

**Alert if**:
- WAL size > max_wal_size (checkpoint not triggering)
- WAL growth rate abnormal (replication lag)

### 2. Configure Checkpoints Appropriately

**Trade-offs**:
- Frequent checkpoints: Slower performance, faster recovery
- Infrequent checkpoints: Faster performance, slower recovery

**Recommendation**:
- `checkpoint_timeout`: 5-15 minutes
- `max_wal_size`: 1-4 GB
- Monitor actual recovery time in test crashes

### 3. Use Correlation IDs

Track transactions for recovery verification:

```java
String correlationId = UUID.randomUUID().toString();
transaction.setCorrelationId(correlationId);
```

After crash:
```bash
curl "...recovery/verify?correlationId=${correlationId}"
```

### 4. Test Recovery Regularly

**Monthly drill**:
1. Perform test transactions with known correlation IDs
2. Simulate crash (`kill-db.sh --hard-kill`)
3. Verify recovery within SLO
4. Document recovery time

### 5. Monitor Recovery Metrics

```promql
# Recent restart (potential crash)
postgresql_uptime_seconds < 600

# WAL replay during recovery
rate(postgresql_wal_replay_bytes[1m])

# Recovery completion
postgresql_in_recovery == 1  # Still recovering
```

### 6. Understand Commit Semantics

**Synchronous commit (default)**:
```sql
SET synchronous_commit = on;
COMMIT;  -- Returns AFTER WAL flushed to disk
```
- Slower (fsync latency)
- Guaranteed durability

**Asynchronous commit**:
```sql
SET synchronous_commit = off;
COMMIT;  -- Returns BEFORE WAL flushed
```
- Faster (no fsync wait)
- Risk: Last few transactions may be lost on crash
- Use only for non-critical data

## Troubleshooting

### Recovery Stuck

**Symptoms**:
- Database takes very long to start
- `postgresql_in_recovery == 1` for extended period

**Causes**:
- Large amount of WAL to replay
- Corrupted WAL files
- Disk I/O bottleneck

**Solutions**:
1. Check disk I/O: `iostat -x 1`
2. Review PostgreSQL logs: `docker logs postgres`
3. If WAL corrupted, restore from backup

### Missing Transactions After Recovery

**Symptoms**:
- Committed transactions not found
- Unexpected data loss

**Investigation**:
1. Check WAL settings: `SHOW wal_level`
2. Verify fsync enabled: `SHOW fsync`
3. Check for disk errors: `dmesg | grep -i error`
4. Review backup restore (if backup used)

**Prevention**:
- Always use `fsync = on` (default)
- Monitor disk health
- Test recovery procedures regularly

### Long Recovery Time

**If recovery takes too long**:

1. **Increase checkpoint frequency**:
   ```sql
   ALTER SYSTEM SET checkpoint_timeout = '3min';
   ALTER SYSTEM SET max_wal_size = '512MB';
   ```

2. **Improve disk I/O**:
   - Use faster disks (SSD)
   - Increase `shared_buffers`
   - Optimize filesystem (XFS, ext4)

3. **Monitor checkpoint stats**:
   ```sql
   SELECT * FROM pg_stat_bgwriter;
   ```

## Summary

This demonstration shows how to:

✅ Understand PostgreSQL WAL mechanism
✅ Verify WAL configuration and operation
✅ Simulate database crashes safely
✅ Verify committed transaction durability
✅ Use point-in-time recovery queries
✅ Monitor recovery statistics
✅ Test crash recovery procedures
✅ Troubleshoot recovery issues

**Key Takeaways**:
- WAL ensures durability: Committed transactions survive crashes
- Recovery is automatic: PostgreSQL replays WAL on startup
- Uncommitted transactions are rolled back
- Regular testing ensures recovery procedures work
- Monitoring detects potential issues before failures

For more demonstrations:
- [Failure Handling and Resilience](./failure-handling.md)
- [Distributed Tracing](../observability/tracing.md)
- [ACID Durability](../acid/durability.md)

## References

- [PostgreSQL WAL Documentation](https://www.postgresql.org/docs/current/wal-intro.html)
- [PostgreSQL Crash Recovery](https://www.postgresql.org/docs/current/wal-configuration.html)
- [Point-in-Time Recovery](https://www.postgresql.org/docs/current/continuous-archiving.html)
- [Checkpoint Tuning](https://www.postgresql.org/docs/current/wal-configuration.html#RUNTIME-CONFIG-WAL-CHECKPOINTS)
