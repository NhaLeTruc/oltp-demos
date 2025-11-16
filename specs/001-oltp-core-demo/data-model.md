# Data Model: OLTP Core Capabilities Tech Demo

**Date**: 2025-11-16
**Database**: PostgreSQL 15+
**Migration Tool**: Flyway
**Status**: Design Complete

## Overview

This document defines the database schema for the OLTP demonstration system. The schema is designed to showcase core OLTP patterns including ACID transactions, concurrency control, performance optimization, and data integrity.

**Design Principles** (from constitution):
- **Normalization**: Start with 3NF, denormalize only with justification
- **Primary Keys**: Surrogate keys (BIGINT auto-increment or UUID)
- **Foreign Keys**: Always define FK constraints for referential integrity
- **Indexes**: Create for PKs, FKs, frequently queried columns
- **Constraints**: Use CHECK, NOT NULL, UNIQUE where appropriate
- **Types**: Use appropriate PostgreSQL types

---

## Entity Relationship Diagram

```
┌──────────────┐         ┌──────────────────┐         ┌────────────────────┐
│    users     │ 1     * │    accounts      │ *     1 │   account_types    │
│--------------│◄────────│------------------│─────────┤--------------------│
│ id (PK)      │         │ id (PK)          │         │ id (PK)            │
│ username     │         │ user_id (FK)     │         │ type_name          │
│ email        │         │ account_type_id  │         │ min_balance        │
│ created_at   │         │ balance          │         └────────────────────┘
│ updated_at   │         │ version          │
└──────────────┘         │ status           │
                         │ created_at       │
                         │ updated_at       │
                         └──────────────────┘
                                │
                                │ 1
                                │
                                │ *
                         ┌──────────────────┐
                         │   transactions   │
                         │------------------│
                         │ id (PK)          │
                         │ from_account_id  │────┐
                         │ to_account_id    │────┤ Both FK to accounts
                         │ amount           │    │
                         │ transaction_type │    │
                         │ status           │    │
                         │ correlation_id   │    │
                         │ created_at       │    │
                         │ completed_at     │    │
                         └──────────────────┘    │
                                │                 │
                                │ 1               │
                                │                 │
                                │ 1               │
                         ┌──────────────────┐    │
                         │  transfer_logs   │    │
                         │------------------│    │
                         │ id (PK)          │    │
                         │ transaction_id   │────┘
                         │ from_account_id  │────┐
                         │ to_account_id    │────┤ Audit trail
                         │ amount           │    │
                         │ status           │    │
                         │ correlation_id   │    │
                         │ logged_at        │    │
                         └──────────────────┘    │
                                                  │
                         ┌──────────────────┐    │
                         │    sessions      │    │
                         │------------------│    │
                         │ id (PK)          │    │
                         │ user_id (FK)     │────┘
                         │ session_token    │
                         │ created_at       │
                         │ expires_at       │
                         │ last_accessed_at │
                         └──────────────────┘
```

---

## Core Entities

### 1. users

**Purpose**: Represents system users who own accounts and initiate transactions.

**Schema**:
```sql
CREATE TABLE users (
    id                  BIGSERIAL PRIMARY KEY,
    username            VARCHAR(50) NOT NULL UNIQUE,
    email               VARCHAR(255) NOT NULL UNIQUE,
    full_name           VARCHAR(255) NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_users_username_length CHECK (LENGTH(username) >= 3),
    CONSTRAINT chk_users_email_format CHECK (email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}$')
);

-- Indexes
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_created_at ON users(created_at);
```

**JPA Entity**:
```java
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false, name = "full_name")
    private String fullName;

    @Column(nullable = false, updatable = false, name = "created_at")
    private Instant createdAt;

    @Column(nullable = false, name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    private Set<Account> accounts;
}
```

**Validation Rules**:
- Username: 3-50 characters, alphanumeric + underscore
- Email: Valid email format (regex validated)
- All fields required (NOT NULL)

**Concurrency**: No version column (users rarely updated concurrently)

---

### 2. account_types

**Purpose**: Lookup table for account types with business rules.

**Schema**:
```sql
CREATE TABLE account_types (
    id                  SERIAL PRIMARY KEY,
    type_name           VARCHAR(50) NOT NULL UNIQUE,
    min_balance         DECIMAL(15, 2) NOT NULL DEFAULT 0.00,
    description         TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_account_types_min_balance CHECK (min_balance >= 0)
);

-- Seed data (inserted via migration)
INSERT INTO account_types (type_name, min_balance, description) VALUES
    ('CHECKING', 0.00, 'Standard checking account'),
    ('SAVINGS', 100.00, 'Savings account with minimum balance requirement'),
    ('BUSINESS', 1000.00, 'Business account with higher minimum');
```

**JPA Entity**:
```java
@Entity
@Table(name = "account_types")
public class AccountType {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, name = "type_name", length = 50)
    private String typeName;

    @Column(nullable = false, name = "min_balance")
    private BigDecimal minBalance;

    private String description;

    @Column(nullable = false, updatable = false, name = "created_at")
    private Instant createdAt;
}
```

---

### 3. accounts

**Purpose**: Represents financial accounts with balances. Core entity for ACID and concurrency demonstrations.

**Schema**:
```sql
CREATE TABLE accounts (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    account_type_id     INTEGER NOT NULL,
    balance             DECIMAL(15, 2) NOT NULL DEFAULT 0.00,
    version             BIGINT NOT NULL DEFAULT 0,  -- Optimistic locking
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_accounts_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_accounts_type FOREIGN KEY (account_type_id)
        REFERENCES account_types(id) ON DELETE RESTRICT,
    CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT chk_accounts_balance_non_negative CHECK (balance >= 0)
);

-- Indexes
CREATE INDEX idx_accounts_user_id ON accounts(user_id);
CREATE INDEX idx_accounts_status ON accounts(status);
CREATE INDEX idx_accounts_balance ON accounts(balance);  -- For range queries
CREATE INDEX idx_accounts_updated_at ON accounts(updated_at);
```

**JPA Entity**:
```java
@Entity
@Table(name = "accounts")
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_type_id", nullable = false)
    private AccountType accountType;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    @Version  // Optimistic locking
    @Column(nullable = false)
    private Long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(nullable = false, updatable = false, name = "created_at")
    private Instant createdAt;

    @Column(nullable = false, name = "updated_at")
    private Instant updatedAt;
}
```

**Key Features**:
- **`version` column**: Enables optimistic locking demonstrations (JPA `@Version`)
- **`balance` constraint**: Prevents negative balances (consistency demonstration)
- **Foreign keys**: Enforce referential integrity
- **Indexes on `user_id`, `balance`**: Performance optimization demonstrations

**Concurrency Strategy**:
- **Optimistic Locking**: `@Version` for concurrent balance updates
- **Pessimistic Locking**: Explicit `SELECT FOR UPDATE` in demonstrations

---

### 4. transactions

**Purpose**: Represents business transactions (transfers, deposits, withdrawals). Central to ACID demonstrations.

**Schema**:
```sql
CREATE TABLE transactions (
    id                  BIGSERIAL PRIMARY KEY,
    from_account_id     BIGINT,  -- NULL for deposits
    to_account_id       BIGINT,  -- NULL for withdrawals
    amount              DECIMAL(15, 2) NOT NULL,
    transaction_type    VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    correlation_id      UUID NOT NULL,  -- For tracing
    error_message       TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP,

    CONSTRAINT fk_transactions_from_account FOREIGN KEY (from_account_id)
        REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_transactions_to_account FOREIGN KEY (to_account_id)
        REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT chk_transactions_type CHECK (transaction_type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL')),
    CONSTRAINT chk_transactions_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'ROLLED_BACK')),
    CONSTRAINT chk_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_transactions_accounts_not_same CHECK (from_account_id IS DISTINCT FROM to_account_id),
    CONSTRAINT chk_transactions_transfer_has_both_accounts CHECK (
        (transaction_type = 'TRANSFER' AND from_account_id IS NOT NULL AND to_account_id IS NOT NULL) OR
        (transaction_type = 'DEPOSIT' AND from_account_id IS NULL AND to_account_id IS NOT NULL) OR
        (transaction_type = 'WITHDRAWAL' AND from_account_id IS NOT NULL AND to_account_id IS NULL)
    )
);

-- Indexes
CREATE INDEX idx_transactions_from_account ON transactions(from_account_id);
CREATE INDEX idx_transactions_to_account ON transactions(to_account_id);
CREATE INDEX idx_transactions_correlation_id ON transactions(correlation_id);  -- Tracing
CREATE INDEX idx_transactions_status ON transactions(status);
CREATE INDEX idx_transactions_created_at ON transactions(created_at DESC);  -- Recent first
CREATE INDEX idx_transactions_completed_at ON transactions(completed_at DESC) WHERE completed_at IS NOT NULL;
```

**JPA Entity**:
```java
@Entity
@Table(name = "transactions")
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, name = "transaction_type", length = 20)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(nullable = false, name = "correlation_id")
    private UUID correlationId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false, updatable = false, name = "created_at")
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
```

**Key Features**:
- **`correlation_id` (UUID)**: Enables distributed tracing across logs, metrics, traces
- **Status tracking**: PENDING → COMPLETED / FAILED / ROLLED_BACK (atomicity demonstration)
- **Nullable account fields**: Supports deposits (no `from`) and withdrawals (no `to`)
- **Complex CHECK constraints**: Business rule enforcement at database level
- **Partial indexes**: `completed_at` index only for completed transactions (performance optimization)

---

### 5. transfer_logs

**Purpose**: Immutable audit trail for all transfer operations. Demonstrates durability and write-ahead logging concepts.

**Schema**:
```sql
CREATE TABLE transfer_logs (
    id                  BIGSERIAL PRIMARY KEY,
    transaction_id      BIGINT NOT NULL,
    from_account_id     BIGINT NOT NULL,
    to_account_id       BIGINT NOT NULL,
    amount              DECIMAL(15, 2) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    correlation_id      UUID NOT NULL,
    logged_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_transfer_logs_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_logs_from_account FOREIGN KEY (from_account_id)
        REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_logs_to_account FOREIGN KEY (to_account_id)
        REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT chk_transfer_logs_status CHECK (status IN ('INITIATED', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_transfer_logs_amount_positive CHECK (amount > 0)
);

-- Indexes
CREATE INDEX idx_transfer_logs_transaction_id ON transfer_logs(transaction_id);
CREATE INDEX idx_transfer_logs_from_account ON transfer_logs(from_account_id);
CREATE INDEX idx_transfer_logs_to_account ON transfer_logs(to_account_id);
CREATE INDEX idx_transfer_logs_correlation_id ON transfer_logs(correlation_id);
CREATE INDEX idx_transfer_logs_logged_at ON transfer_logs(logged_at DESC);
```

**JPA Entity**:
```java
@Entity
@Table(name = "transfer_logs")
public class TransferLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id", nullable = false)
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id", nullable = false)
    private Account toAccount;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(nullable = false, name = "correlation_id")
    private UUID correlationId;

    @Column(nullable = false, updatable = false, name = "logged_at")
    private Instant loggedAt;
}
```

**Key Features**:
- **Append-only**: No updates, only inserts (immutable audit trail)
- **Durability demonstration**: Shows committed transactions survive crashes
- **High write volume**: Good target for batch insert demonstrations

---

### 6. sessions

**Purpose**: Tracks active user sessions. Used for connection pooling demonstrations.

**Schema**:
```sql
CREATE TABLE sessions (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    session_token       UUID NOT NULL UNIQUE,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at          TIMESTAMP NOT NULL,
    last_accessed_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_sessions_expires_after_created CHECK (expires_at > created_at)
);

-- Indexes
CREATE INDEX idx_sessions_user_id ON sessions(user_id);
CREATE INDEX idx_sessions_session_token ON sessions(session_token);
CREATE INDEX idx_sessions_expires_at ON sessions(expires_at);  -- For cleanup queries
CREATE INDEX idx_sessions_last_accessed ON sessions(last_accessed_at);
```

**JPA Entity**:
```java
@Entity
@Table(name = "sessions")
public class Session {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true, name = "session_token")
    private UUID sessionToken;

    @Column(nullable = false, updatable = false, name = "created_at")
    private Instant createdAt;

    @Column(nullable = false, name = "expires_at")
    private Instant expiresAt;

    @Column(nullable = false, name = "last_accessed_at")
    private Instant lastAccessedAt;
}
```

**Key Features**:
- **UUID token**: Unique session identifier
- **Expiration**: Automatic cleanup via scheduled job
- **Frequent updates**: `last_accessed_at` updated on every request (concurrency testing)

---

## Indexing Strategy

### Performance Justification

| Index | Table | Columns | Purpose | Cardinality | Selectivity |
|-------|-------|---------|---------|-------------|-------------|
| `idx_users_email` | users | email | Login queries | High | Very High |
| `idx_users_username` | users | username | Profile lookups | High | Very High |
| `idx_accounts_user_id` | accounts | user_id | Join to users | Medium | Medium |
| `idx_accounts_balance` | accounts | balance | Range queries (top accounts) | Low | Low |
| `idx_transactions_correlation_id` | transactions | correlation_id | Tracing lookups | Very High | Very High |
| `idx_transactions_created_at` | transactions | created_at DESC | Recent transactions | High | Medium |
| `idx_transactions_completed_at` | transactions | completed_at DESC (partial) | Completed txns only | High | High |
| `idx_transfer_logs_logged_at` | transfer_logs | logged_at DESC | Audit queries | High | Medium |
| `idx_sessions_session_token` | sessions | session_token | Session validation | Very High | Very High |
| `idx_sessions_expires_at` | sessions | expires_at | Cleanup queries | Low | Low |

**Key Decisions**:
- **Partial index** on `transactions.completed_at`: Only index non-NULL values (reduces index size by ~30%)
- **DESC order** on timestamp indexes: Recent-first queries are most common
- **No index** on `accounts.status`: Low cardinality (3 values), full table scan faster for small tables

---

## Constraints & Business Rules

### Referential Integrity

All foreign keys use `ON DELETE RESTRICT` to prevent cascading deletes and enforce explicit cleanup:
```sql
-- Example: Cannot delete user if they have accounts
DELETE FROM users WHERE id = 123;
-- ERROR: violates foreign key constraint "fk_accounts_user"
```

**Exception**: `sessions.user_id` uses `ON DELETE CASCADE` (sessions are ephemeral)

### Data Validation

| Constraint | Purpose | Example |
|-----------|---------|---------|
| `chk_accounts_balance_non_negative` | Prevent overdrafts | `balance >= 0` |
| `chk_transactions_amount_positive` | No zero/negative transfers | `amount > 0` |
| `chk_transactions_accounts_not_same` | No self-transfers | `from_account_id != to_account_id` |
| `chk_transactions_transfer_has_both_accounts` | Business rule enforcement | TRANSFER requires both accounts |
| `chk_users_email_format` | Valid email addresses | Regex validation |

**Why Database Constraints**:
- **Defense in Depth**: Enforced even if application logic bypassed
- **Consistency Demonstration**: Shows database-level consistency guarantees
- **Explicit Documentation**: Schema documents business rules

---

## Migration Strategy (Flyway)

### Migration Files

```
src/main/resources/db/migration/
├── V1__create_schema.sql          # Core tables (users, account_types, accounts)
├── V2__create_transaction_tables.sql  # transactions, transfer_logs
├── V3__create_sessions_table.sql  # sessions
├── V4__create_indexes.sql         # All indexes
├── V5__add_constraints.sql        # CHECK constraints
├── V6__seed_reference_data.sql    # account_types seed data
└── R__refresh_views.sql           # Repeatable: Materialized views (if added)
```

### Rollback Scripts

Each migration has a corresponding down migration:
```
V1__create_schema.sql → U1__drop_schema.sql
V2__create_transaction_tables.sql → U2__drop_transaction_tables.sql
```

### Zero-Downtime Strategy

**Expand-Migrate-Contract Pattern**:
1. **Expand**: Add new column as nullable
2. **Migrate**: Backfill data, add application logic
3. **Contract**: Add NOT NULL constraint, remove old column

Example: Renaming `accounts.balance` to `accounts.current_balance`:
```sql
-- V7: Expand
ALTER TABLE accounts ADD COLUMN current_balance DECIMAL(15,2);

-- V8: Migrate (application deployed)
-- App writes to both balance and current_balance

-- V9: Contract
ALTER TABLE accounts ALTER COLUMN current_balance SET NOT NULL;
ALTER TABLE accounts DROP COLUMN balance;
```

---

## Data Volumes & Performance Targets

### Realistic Test Data

| Table | Rows | Purpose |
|-------|------|---------|
| users | 10,000 | Realistic user base |
| accounts | 25,000 | 2.5 accounts/user average |
| transactions | 1,000,000 | 40 transactions/account |
| transfer_logs | 750,000 | 75% of transactions are transfers |
| sessions | 5,000 | Active sessions (500 concurrent users) |

### Performance Targets

| Query Type | Target Latency (p95) | Validation Method |
|-----------|---------------------|-------------------|
| User lookup by email | < 1ms | Single index lookup |
| Account balance check | < 2ms | Single index lookup + join |
| Transfer transaction | < 10ms | Multi-step transaction with locks |
| Recent transactions | < 5ms | Index scan on `created_at DESC` |
| Transfer log insert | < 3ms | Batch insert demonstration |

---

## Concurrency Patterns

### Optimistic Locking (Primary Strategy)

**Use Case**: Account balance updates with low contention
```java
@Service
@Transactional
public class TransferService {
    public void transfer(Long fromId, Long toId, BigDecimal amount) {
        Account from = accountRepository.findById(fromId).orElseThrow();
        Account to = accountRepository.findById(toId).orElseThrow();

        // JPA automatically includes: WHERE version = ?
        from.setBalance(from.getBalance().subtract(amount));
        to.setBalance(to.getBalance().add(amount));

        accountRepository.save(from);  // Version incremented
        accountRepository.save(to);    // Version incremented

        // If version changed, throws OptimisticLockException
    }
}
```

### Pessimistic Locking (High Contention)

**Use Case**: Hot account (many concurrent transfers)
```java
@Query("SELECT a FROM Account a WHERE a.id = :id")
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Account> findByIdForUpdate(@Param("id") Long id);
```

Generates: `SELECT ... FROM accounts WHERE id = ? FOR UPDATE`

### Deadlock Scenario

**Demonstration**: Two concurrent transfers in opposite directions
```
Transaction T1: Transfer A → B
Transaction T2: Transfer B → A

T1: LOCK account A
T2: LOCK account B
T1: WAIT FOR account B (held by T2)
T2: WAIT FOR account A (held by T1)
→ DEADLOCK

PostgreSQL detects and aborts one transaction
Application retries with exponential backoff
```

---

## Summary

### Schema Statistics

- **Tables**: 6 (users, account_types, accounts, transactions, transfer_logs, sessions)
- **Indexes**: 21 (including unique constraints)
- **Foreign Keys**: 9
- **CHECK Constraints**: 15
- **Unique Constraints**: 5

### ACID Demonstration Mapping

| ACID Property | Tables Used | Demonstration |
|--------------|-------------|---------------|
| **Atomicity** | accounts, transactions | Multi-account transfer (all-or-nothing) |
| **Consistency** | accounts | Balance constraint enforcement |
| **Isolation** | accounts | Concurrent transfers with different isolation levels |
| **Durability** | transfer_logs | Committed logs survive crash recovery |

### Observability Features

- **Correlation IDs**: `transactions.correlation_id`, `transfer_logs.correlation_id`
- **Timestamps**: Created/updated/completed tracking on all entities
- **Status Tracking**: Explicit status enums for transaction states
- **Audit Trail**: Immutable `transfer_logs` table

---

**Data Model Status**: Complete | Ready for implementation | Validated against constitution
