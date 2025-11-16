# Database Indexing Strategy

## Index Design Principles

1. **Query-driven**: Indexes based on actual query patterns
2. **Read-optimized**: OLTP reads outnumber writes 10:1
3. **Selective**: Index columns with high cardinality
4. **Composite where needed**: Multi-column indexes for common filters
5. **Monitor usage**: Track and remove unused indexes

## Primary Keys (Clustered Indexes)

All tables use **BIGSERIAL** (auto-incrementing 64-bit integer):
- Efficient for sequential inserts
- Compact (8 bytes vs UUID's 16 bytes)
- Natural ordering for time-series queries

```sql
id BIGSERIAL PRIMARY KEY
```

## Index Catalog

### users Table

| Index Name | Columns | Type | Purpose | Selectivity |
|------------|---------|------|---------|-------------|
| users_pkey | id | B-tree (PK) | Primary access | Unique |
| users_email_idx | email | B-tree UNIQUE | Login queries | Unique |
| users_status_idx | status | B-tree | Filter active users | Medium (3 values) |

**Queries Served**:
```sql
-- Primary key lookup
SELECT * FROM users WHERE id = ?;

-- Login
SELECT * FROM users WHERE email = ?;

-- Active users
SELECT * FROM users WHERE status = 'ACTIVE';
```

---

### accounts Table

| Index Name | Columns | Type | Purpose | Selectivity |
|------------|---------|------|---------|-------------|
| accounts_pkey | id | B-tree (PK) | Primary access | Unique |
| accounts_user_id_idx | user_id | B-tree | User's accounts | High |
| accounts_account_type_id_idx | account_type_id | B-tree | Accounts by type | Low (3 types) |
| accounts_account_number_idx | account_number | B-tree UNIQUE | Lookup by number | Unique |
| accounts_status_idx | status | B-tree | Filter by status | Medium (4 values) |
| accounts_user_status_idx | (user_id, status) | B-tree | Composite filter | High |

**Queries Served**:
```sql
-- Transfer lookup (hot path)
SELECT * FROM accounts WHERE id = ?;

-- User's accounts
SELECT * FROM accounts WHERE user_id = ?;

-- User's active accounts
SELECT * FROM accounts WHERE user_id = ? AND status = 'ACTIVE';

-- Account number lookup
SELECT * FROM accounts WHERE account_number = ?;
```

**Composite Index Rationale**:
- `(user_id, status)`: Common pattern in UI (show user's active accounts)
- Eliminates need for separate status filter after user_id lookup

---

### transactions Table

| Index Name | Columns | Type | Purpose | Selectivity |
|------------|---------|------|---------|-------------|
| transactions_pkey | id | B-tree (PK) | Primary access | Unique |
| transactions_from_account_idx | from_account_id | B-tree | Source account history | High |
| transactions_to_account_idx | to_account_id | B-tree | Dest account history | High |
| transactions_status_idx | status | B-tree | Filter by status | Medium (3 values) |
| transactions_created_at_idx | created_at | B-tree | Time-range queries | High |
| transactions_correlation_id_idx | correlation_id | B-tree UNIQUE | Idempotency checks | Unique |

**Queries Served**:
```sql
-- Account transaction history
SELECT * FROM transactions WHERE from_account_id = ? ORDER BY created_at DESC;
SELECT * FROM transactions WHERE to_account_id = ? ORDER BY created_at DESC;

-- Time-range analysis
SELECT * FROM transactions WHERE created_at BETWEEN ? AND ?;

-- Idempotency check
SELECT * FROM transactions WHERE correlation_id = ?;

-- Failed transactions
SELECT * FROM transactions WHERE status = 'FAILED';
```

**created_at Index**:
- Supports time-range queries for reporting
- Helps with ORDER BY in pagination
- Used in partition pruning (future enhancement)

---

### transfer_logs Table (Audit Trail)

| Index Name | Columns | Type | Purpose | Selectivity |
|------------|---------|------|---------|-------------|
| transfer_logs_pkey | id | B-tree (PK) | Primary access | Unique |
| transfer_logs_transaction_id_idx | transaction_id | B-tree | Lookup by transaction | Unique (1:1) |
| transfer_logs_created_at_idx | created_at | B-tree | Time-range queries | High |

**Queries Served**:
```sql
-- Audit lookup
SELECT * FROM transfer_logs WHERE transaction_id = ?;

-- Audit history
SELECT * FROM transfer_logs WHERE created_at BETWEEN ? AND ?;
```

---

### sessions Table

| Index Name | Columns | Type | Purpose | Selectivity |
|------------|---------|------|---------|-------------|
| sessions_pkey | id | B-tree (PK) | Primary access | Unique |
| sessions_session_token_idx | session_token | B-tree UNIQUE | Token lookup | Unique |
| sessions_user_id_idx | user_id | B-tree | User sessions | Medium |
| sessions_expires_at_idx | expires_at | B-tree | Cleanup expired | High |

**Queries Served**:
```sql
-- Session validation
SELECT * FROM sessions WHERE session_token = ?;

-- User sessions
SELECT * FROM sessions WHERE user_id = ?;

-- Cleanup job
DELETE FROM sessions WHERE expires_at < NOW();
```

## Index Maintenance

### Monitoring Index Usage

```sql
-- Unused indexes (candidates for removal)
SELECT
    schemaname,
    tablename,
    indexname,
    idx_scan,
    idx_tup_read,
    idx_tup_fetch
FROM pg_stat_user_indexes
WHERE idx_scan = 0
    AND indexrelname NOT LIKE '%_pkey'
ORDER BY pg_relation_size(indexrelid) DESC;
```

### Index Bloat Detection

```sql
-- Check index bloat
SELECT
    tablename,
    indexname,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
    idx_scan,
    idx_tup_read,
    idx_tup_fetch
FROM pg_stat_user_indexes
ORDER BY pg_relation_size(indexrelid) DESC;
```

### Reindex Strategy

```sql
-- Rebuild indexes (low-traffic periods)
REINDEX INDEX CONCURRENTLY accounts_user_status_idx;

-- Rebuild table (all indexes)
REINDEX TABLE CONCURRENTLY accounts;
```

## Performance Considerations

### Index Size Overhead

| Table | Data Size | Index Size | Overhead |
|-------|-----------|------------|----------|
| users | 15 MB | 5 MB | 33% |
| accounts | 40 MB | 18 MB | 45% |
| transactions | 180 MB | 90 MB | 50% |
| transfer_logs | 150 MB | 45 MB | 30% |
| **Total** | **385 MB** | **158 MB** | **41%** |

**Acceptable**: Index overhead < 50% is typical for OLTP systems.

### Write Impact

Each index adds ~10-20% write overhead:
- 6 indexes on `accounts` = ~60-120% overhead
- Trade-off: Read performance gain >> write cost

### Index Selectivity

**High selectivity** (good candidates):
- Unique columns (email, account_number)
- High-cardinality columns (user_id, created_at)

**Low selectivity** (use sparingly):
- account_type_id (only 3 values)
- status fields (3-4 values)

**Recommendation**: Low-selectivity indexes only if query frequency is very high.

## Query Optimization Examples

### Example 1: Account Lookup
```sql
-- Query
SELECT * FROM accounts WHERE id = 1;

-- Execution Plan
Index Scan using accounts_pkey on accounts
  Index Cond: (id = 1)
  Planning Time: 0.1 ms
  Execution Time: 0.05 ms
```

### Example 2: User's Active Accounts
```sql
-- Query
SELECT * FROM accounts WHERE user_id = 123 AND status = 'ACTIVE';

-- Execution Plan
Index Scan using accounts_user_status_idx on accounts
  Index Cond: ((user_id = 123) AND (status = 'ACTIVE'::text))
  Planning Time: 0.1 ms
  Execution Time: 0.3 ms
```

### Example 3: Transaction History
```sql
-- Query
SELECT * FROM transactions
WHERE from_account_id = 1
ORDER BY created_at DESC
LIMIT 20;

-- Execution Plan
Limit
  -> Index Scan Backward using transactions_created_at_idx on transactions
       Filter: (from_account_id = 1)
  Planning Time: 0.1 ms
  Execution Time: 1.2 ms
```

**Note**: This query uses created_at index, not from_account_id. Consider composite index `(from_account_id, created_at)` if this query is frequent.

## Future Enhancements

### Partial Indexes
```sql
-- Only index active accounts (smaller, faster)
CREATE INDEX accounts_active_idx ON accounts (user_id)
WHERE status = 'ACTIVE';
```

### Covering Indexes
```sql
-- Include balance in index (avoid table lookup)
CREATE INDEX accounts_user_covering_idx ON accounts (user_id)
INCLUDE (balance, status);
```

### Expression Indexes
```sql
-- Index on lower-case email for case-insensitive search
CREATE INDEX users_email_lower_idx ON users (LOWER(email));
```

## References

- [PostgreSQL Index Types](https://www.postgresql.org/docs/current/indexes-types.html)
- [PostgreSQL Index Optimization](https://www.postgresql.org/docs/current/indexes-examine.html)
- [Use The Index, Luke!](https://use-the-index-luke.com/)
