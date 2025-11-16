# Database Schema Documentation

**Database**: PostgreSQL 15+
**Schema**: public
**Migrations**: Flyway (V1-V6)

## Entity Relationship Diagram

```
users (1) ──< (N) accounts (N) >── (1) account_types
             accounts (1) ──< (N) transactions
          transactions (1) ──< (N) transfer_logs
                        sessions (standalone)
```

## Tables

### users
Stores user information.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGSERIAL | PRIMARY KEY | Unique user identifier |
| email | VARCHAR(255) | NOT NULL, UNIQUE | User email address |
| status | VARCHAR(20) | NOT NULL, CHECK(status IN ('ACTIVE','INACTIVE','SUSPENDED')) | Account status |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | Record creation time |
| updated_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | Last update time |

**Indexes**:
- PRIMARY KEY on `id`
- UNIQUE INDEX on `email`
- INDEX on `status`

---

### account_types
Reference data for account types.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGSERIAL | PRIMARY KEY | Unique type identifier |
| name | VARCHAR(50) | NOT NULL, UNIQUE | Type name (CHECKING, SAVINGS, CREDIT) |
| description | VARCHAR(255) | | Type description |

**Seed Data**:
1. CHECKING - Standard checking account
2. SAVINGS - High-interest savings account
3. CREDIT - Credit card account

---

### accounts
Core account entity with optimistic locking.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGSERIAL | PRIMARY KEY | Unique account identifier |
| user_id | BIGINT | NOT NULL, FOREIGN KEY → users(id) | Account owner |
| account_type_id | BIGINT | NOT NULL, FOREIGN KEY → account_types(id) | Account type |
| account_number | VARCHAR(50) | NOT NULL, UNIQUE | Account number |
| balance | DECIMAL(15,2) | NOT NULL, CHECK(balance >= 0) | Current balance |
| status | VARCHAR(20) | NOT NULL, CHECK(status IN ('ACTIVE','INACTIVE','FROZEN','CLOSED')) | Account status |
| version | BIGINT | NOT NULL, DEFAULT 0 | Optimistic lock version |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | Record creation time |
| updated_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | Last update time |

**Indexes**:
- PRIMARY KEY on `id`
- INDEX on `user_id`
- INDEX on `account_type_id`
- UNIQUE INDEX on `account_number`
- INDEX on `status`
- INDEX on `(user_id, status)`

**Constraints**:
- `balance >= 0` - Prevents negative balances
- `status IN (...)` - Valid status values only

---

### transactions
Transfer transaction records.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGSERIAL | PRIMARY KEY | Unique transaction ID |
| from_account_id | BIGINT | NOT NULL, FOREIGN KEY → accounts(id) | Source account |
| to_account_id | BIGINT | NOT NULL, FOREIGN KEY → accounts(id) | Destination account |
| amount | DECIMAL(15,2) | NOT NULL, CHECK(amount > 0) | Transfer amount |
| status | VARCHAR(20) | NOT NULL, CHECK(status IN ('PENDING','SUCCESS','FAILED')) | Transaction status |
| correlation_id | VARCHAR(255) | UNIQUE | Request correlation ID |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | Transaction time |
| updated_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | Last status update |

**Indexes**:
- PRIMARY KEY on `id`
- INDEX on `from_account_id`
- INDEX on `to_account_id`
- INDEX on `status`
- INDEX on `created_at`
- UNIQUE INDEX on `correlation_id`

**Constraints**:
- `amount > 0` - Transfers must be positive
- `from_account_id != to_account_id` - Cannot transfer to same account

---

### transfer_logs
Audit trail for transfers (immutable).

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGSERIAL | PRIMARY KEY | Unique log ID |
| transaction_id | BIGINT | NOT NULL, FOREIGN KEY → transactions(id) | Related transaction |
| from_account_balance_before | DECIMAL(15,2) | NOT NULL | Source balance before |
| from_account_balance_after | DECIMAL(15,2) | NOT NULL | Source balance after |
| to_account_balance_before | DECIMAL(15,2) | NOT NULL | Dest balance before |
| to_account_balance_after | DECIMAL(15,2) | NOT NULL | Dest balance after |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | Log entry time |

**Indexes**:
- PRIMARY KEY on `id`
- INDEX on `transaction_id`
- INDEX on `created_at`

---

### sessions
Connection pool demonstration data.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGSERIAL | PRIMARY KEY | Unique session ID |
| user_id | BIGINT | FOREIGN KEY → users(id) | Associated user |
| session_token | VARCHAR(255) | NOT NULL, UNIQUE | Session identifier |
| expires_at | TIMESTAMP | NOT NULL | Expiration time |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | Session creation time |

**Indexes**:
- PRIMARY KEY on `id`
- UNIQUE INDEX on `session_token`
- INDEX on `user_id`
- INDEX on `expires_at`

## Data Volumes

Expected row counts for demonstrations:
- **users**: 100K rows
- **account_types**: 3 rows (reference data)
- **accounts**: 200K rows (2 accounts per user avg)
- **transactions**: 1M rows
- **transfer_logs**: 1M rows
- **sessions**: 10K active sessions

## Storage Estimates

| Table | Rows | Avg Row Size | Total Size |
|-------|------|--------------|------------|
| users | 100K | ~150 bytes | ~15 MB |
| accounts | 200K | ~200 bytes | ~40 MB |
| transactions | 1M | ~180 bytes | ~180 MB |
| transfer_logs | 1M | ~150 bytes | ~150 MB |
| sessions | 10K | ~200 bytes | ~2 MB |
| **Total** | | | **~387 MB** |

*Note: Indexes add ~30-50% overhead (~580 MB total)*

## Performance Tuning

### Vacuum Strategy
```sql
-- Autovacuum settings
ALTER TABLE transactions SET (
    autovacuum_vacuum_scale_factor = 0.1,
    autovacuum_analyze_scale_factor = 0.05
);
```

### Analyze Statistics
```sql
-- Update statistics after bulk load
ANALYZE users;
ANALYZE accounts;
ANALYZE transactions;
```

## Migration History

| Version | Description | Date |
|---------|-------------|------|
| V1 | Create core schema (users, accounts, account_types) | Initial |
| V2 | Create transaction tables | Initial |
| V3 | Create sessions table | Initial |
| V4 | Add all indexes | Initial |
| V5 | Add CHECK constraints | Initial |
| V6 | Seed reference data (account_types) | Initial |
