# ACID Consistency Demonstration

**Demonstration**: Database constraint enforcement and data integrity

**User Story**: US1 - ACID Transaction Guarantees (T077)

**Functional Requirements**: FR-002

## Overview

Consistency is the "C" in ACID and ensures that a transaction brings the database from one valid state to another valid state. All constraints, cascades, triggers, and business rules must be satisfied before a transaction can commit.

### What You'll Learn

- CHECK constraints for business rules
- Foreign key constraints for referential integrity
- Unique constraints
- NOT NULL constraints
- Constraint violation handling
- Validation before persistence

### Prerequisites

- Application running on `localhost:8080`
- PostgreSQL database with constraints defined
- Understanding of database constraints

## The Consistency Guarantee

```
┌─────────────────────────────────────────┐
│ Database Constraints                    │
│                                         │
│  ✓ CHECK: balance >= 0                 │
│  ✓ FOREIGN KEY: account_type_id        │
│  ✓ NOT NULL: status, created_at        │
│  ✓ UNIQUE: (user_id, account_number)   │
│                                         │
│  Transaction:                           │
│  ┌──────────────────────────┐          │
│  │ UPDATE accounts          │          │
│  │ SET balance = -100       │          │
│  └──────────────────────────┘          │
│           │                             │
│           ▼                             │
│  ┌──────────────────────────┐          │
│  │ CHECK balance >= 0       │          │
│  │ FAILS!                   │          │
│  └──────────────────────────┘          │
│           │                             │
│           ▼                             │
│      ROLLBACK                           │
│                                         │
│  Database remains consistent            │
└─────────────────────────────────────────┘
```

## Demonstrations

### 1. Negative Balance Prevention (CHECK Constraint)

**Scenario**: Attempt to create negative balance, constraint prevents it

```bash
# Attempt transfer that would cause negative balance
curl -X POST http://localhost:8080/api/demos/acid/consistency/enforce-constraints \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: consistency-demo-001' \
  -d '{
    "type": "NEGATIVE_BALANCE",
    "accountId": 1,
    "amount": 999999.00
  }'
```

**Response** (constraint violation):
```json
{
  "success": false,
  "constraintViolated": "balance_non_negative",
  "error": "CheckConstraintViolationException",
  "message": "CHECK constraint failed: balance must be >= 0",
  "detail": "Attempted to set balance to -999899.00 for account 1",
  "sqlState": "23514",
  "correlationId": "consistency-demo-001"
}
```

**Verification**:
```bash
# Verify balance unchanged
curl http://localhost:8080/api/accounts/1
# Balance should remain at original value (e.g., 900.00)
```

**Database Constraint**:
```sql
ALTER TABLE accounts
ADD CONSTRAINT balance_non_negative
CHECK (balance >= 0);
```

**Key Points**:
- ✅ Database prevents invalid state
- ✅ Transaction rolls back automatically
- ✅ Application receives specific error
- ✅ Data integrity preserved

### 2. Foreign Key Constraint

**Scenario**: Attempt to reference non-existent account type

```bash
# Try to create account with invalid account_type_id
curl -X POST http://localhost:8080/api/demos/acid/consistency/enforce-constraints \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: consistency-demo-002' \
  -d '{
    "type": "FOREIGN_KEY",
    "accountTypeId": 99999,
    "userId": 1
  }'
```

**Response** (foreign key violation):
```json
{
  "success": false,
  "constraintViolated": "fk_account_type",
  "error": "ForeignKeyViolationException",
  "message": "Foreign key constraint failed: account_type_id does not exist",
  "detail": "Referenced key not found in account_types table: id=99999",
  "sqlState": "23503",
  "correlationId": "consistency-demo-002"
}
```

**Database Constraint**:
```sql
ALTER TABLE accounts
ADD CONSTRAINT fk_account_type
FOREIGN KEY (account_type_id)
REFERENCES account_types(id);
```

**Key Points**:
- ✅ Referential integrity enforced
- ✅ Cannot create orphaned records
- ✅ Cascade rules apply on DELETE
- ✅ Prevents data corruption

### 3. Status Validation (Enum/Check)

**Scenario**: Attempt to set invalid account status

```bash
# Try to set invalid status value
curl -X POST http://localhost:8080/api/demos/acid/consistency/enforce-constraints \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: consistency-demo-003' \
  -d '{
    "type": "INVALID_STATUS",
    "accountId": 1,
    "status": "INVALID_STATUS"
  }'
```

**Response** (check constraint violation):
```json
{
  "success": false,
  "constraintViolated": "status_valid_values",
  "error": "CheckConstraintViolationException",
  "message": "CHECK constraint failed: status must be ACTIVE, INACTIVE, FROZEN, or CLOSED",
  "detail": "Invalid status value: INVALID_STATUS",
  "sqlState": "23514",
  "correlationId": "consistency-demo-003"
}
```

**Database Constraint**:
```sql
ALTER TABLE accounts
ADD CONSTRAINT status_valid_values
CHECK (status IN ('ACTIVE', 'INACTIVE', 'FROZEN', 'CLOSED'));
```

### 4. NOT NULL Constraint

**Scenario**: Attempt to create record with missing required field

```bash
# Try to create transaction without status
curl -X POST http://localhost:8080/api/demos/acid/consistency/enforce-constraints \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: consistency-demo-004' \
  -d '{
    "type": "NULL_VALUE",
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00
  }'
```

**Response** (NOT NULL violation):
```json
{
  "success": false,
  "constraintViolated": "status_not_null",
  "error": "NotNullViolationException",
  "message": "NOT NULL constraint failed: status cannot be null",
  "detail": "Column 'status' requires a value",
  "sqlState": "23502",
  "correlationId": "consistency-demo-004"
}
```

**Database Constraint**:
```sql
ALTER TABLE transactions
ALTER COLUMN status SET NOT NULL;
```

## Constraint Types

### 1. CHECK Constraints

**Purpose**: Enforce business rules at database level

```sql
-- Balance must be non-negative
ALTER TABLE accounts
ADD CONSTRAINT balance_non_negative
CHECK (balance >= 0);

-- Amount must be positive
ALTER TABLE transactions
ADD CONSTRAINT amount_positive
CHECK (amount > 0);

-- Status must be valid enum value
ALTER TABLE accounts
ADD CONSTRAINT status_valid
CHECK (status IN ('ACTIVE', 'INACTIVE', 'FROZEN', 'CLOSED'));

-- Created date must be in past
ALTER TABLE transactions
ADD CONSTRAINT created_at_past
CHECK (created_at <= CURRENT_TIMESTAMP);
```

### 2. Foreign Key Constraints

**Purpose**: Maintain referential integrity

```sql
-- Account must reference valid user
ALTER TABLE accounts
ADD CONSTRAINT fk_user
FOREIGN KEY (user_id)
REFERENCES users(id)
ON DELETE CASCADE;

-- Transaction must reference valid accounts
ALTER TABLE transactions
ADD CONSTRAINT fk_from_account
FOREIGN KEY (from_account_id)
REFERENCES accounts(id)
ON DELETE RESTRICT;

ALTER TABLE transactions
ADD CONSTRAINT fk_to_account
FOREIGN KEY (to_account_id)
REFERENCES accounts(id)
ON DELETE RESTRICT;
```

### 3. UNIQUE Constraints

**Purpose**: Prevent duplicate data

```sql
-- Email must be unique
ALTER TABLE users
ADD CONSTRAINT email_unique
UNIQUE (email);

-- Account number must be unique per user
ALTER TABLE accounts
ADD CONSTRAINT account_number_unique
UNIQUE (user_id, account_number);
```

### 4. NOT NULL Constraints

**Purpose**: Require mandatory fields

```sql
ALTER TABLE accounts
ALTER COLUMN status SET NOT NULL,
ALTER COLUMN balance SET NOT NULL,
ALTER COLUMN created_at SET NOT NULL;
```

## Application-Level Validation

**JPA Entity Validation**:
```java
@Entity
@Table(name = "accounts")
public class Account {

    @NotNull(message = "Status is required")
    @Column(nullable = false)
    private String status;

    @NotNull(message = "Balance is required")
    @Min(value = 0, message = "Balance cannot be negative")
    @Column(nullable = false)
    private BigDecimal balance;

    @NotNull(message = "Account type is required")
    @ManyToOne
    @JoinColumn(name = "account_type_id", nullable = false)
    private AccountType accountType;
}
```

**Validation Before Persistence**:
```java
@Transactional
public void createAccount(Account account) {
    // Application-level validation
    if (account.getBalance().compareTo(BigDecimal.ZERO) < 0) {
        throw new IllegalArgumentException("Balance cannot be negative");
    }

    if (!isValidStatus(account.getStatus())) {
        throw new IllegalArgumentException("Invalid status: " + account.getStatus());
    }

    // Database-level validation (constraints)
    accountRepository.save(account);
    // If constraints violated, ConstraintViolationException thrown
}
```

## Error Handling

**Constraint Violation Handler**:
```java
@ExceptionHandler(DataIntegrityViolationException.class)
public ResponseEntity<ErrorResponse> handleConstraintViolation(
    DataIntegrityViolationException ex
) {
    String sqlState = extractSqlState(ex);
    String constraintName = extractConstraintName(ex);

    ErrorResponse error = new ErrorResponse();
    error.setConstraintViolated(constraintName);
    error.setSqlState(sqlState);

    // Map SQL state to user-friendly message
    switch (sqlState) {
        case "23514":  // CHECK constraint
            error.setMessage("Business rule violation: " + constraintName);
            break;
        case "23503":  // Foreign key violation
            error.setMessage("Referenced record does not exist");
            break;
        case "23502":  // NOT NULL violation
            error.setMessage("Required field is missing");
            break;
        case "23505":  // UNIQUE violation
            error.setMessage("Duplicate value: " + constraintName);
            break;
    }

    return ResponseEntity.badRequest().body(error);
}
```

## Monitoring Constraint Violations

**Metrics**:
```promql
# Constraint violation rate
rate(constraint_violations{type="check"}[5m])
rate(constraint_violations{type="foreign_key"}[5m])
rate(constraint_violations{type="unique"}[5m])

# Most common violations
topk(5, sum by (constraint_name) (
  increase(constraint_violations[1h])
))
```

**Logs**:
```
2025-11-16 10:30:15.123 [consistency-demo-001] WARN  ConsistencyDemoService - CHECK constraint violated: balance_non_negative
2025-11-16 10:30:15.124 [consistency-demo-001] ERROR ConsistencyDemoService - Transaction rolled back due to constraint violation
2025-11-16 10:30:15.125 [consistency-demo-001] INFO  ConsistencyDemoService - SqlState=23514, Constraint=balance_non_negative
```

## Best Practices

### 1. Layered Validation

✅ **Application Layer** (fast feedback):
```java
if (amount.compareTo(BigDecimal.ZERO) <= 0) {
    throw new IllegalArgumentException("Amount must be positive");
}
```

✅ **Database Layer** (data integrity guarantee):
```sql
CHECK (amount > 0)
```

### 2. Clear Constraint Names

❌ **Bad**:
```sql
ALTER TABLE accounts ADD CHECK (balance >= 0);
-- Generates random name like "accounts_check_1"
```

✅ **Good**:
```sql
ALTER TABLE accounts
ADD CONSTRAINT balance_non_negative
CHECK (balance >= 0);
-- Clear, descriptive name
```

### 3. Appropriate CASCADE Rules

```sql
-- CASCADE: Delete all child records when parent deleted
FOREIGN KEY (user_id) REFERENCES users(id)
ON DELETE CASCADE;  -- Delete accounts when user deleted

-- RESTRICT: Prevent deletion if children exist
FOREIGN KEY (account_id) REFERENCES accounts(id)
ON DELETE RESTRICT;  -- Cannot delete account with transactions

-- SET NULL: Set foreign key to NULL when parent deleted
FOREIGN KEY (manager_id) REFERENCES users(id)
ON DELETE SET NULL;  -- Clear manager reference
```

### 4. Performance Considerations

```sql
-- Create index on foreign key columns
CREATE INDEX idx_accounts_user_id ON accounts(user_id);
CREATE INDEX idx_accounts_account_type_id ON accounts(account_type_id);

-- CHECK constraints are fast (evaluated during DML)
-- No additional index needed for CHECK constraints
```

## Summary

This demonstration shows:

✅ CHECK constraints prevent invalid data (negative balances, invalid statuses)
✅ Foreign key constraints maintain referential integrity
✅ UNIQUE constraints prevent duplicates
✅ NOT NULL constraints enforce required fields
✅ Automatic rollback on constraint violations
✅ Proper error handling and user feedback

**Key Takeaway**: Consistency ensures that only valid data enters the database. Constraints are the last line of defense against data corruption.

For more demonstrations:
- [Atomicity](./atomicity.md) - All-or-nothing transactions
- [Isolation](./isolation.md) - Concurrent transaction handling
- [Durability](./durability.md) - Crash recovery

## References

- [PostgreSQL Constraints](https://www.postgresql.org/docs/current/ddl-constraints.html)
- [JPA Validation](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html)
- [Data Integrity](https://en.wikipedia.org/wiki/Data_integrity)
