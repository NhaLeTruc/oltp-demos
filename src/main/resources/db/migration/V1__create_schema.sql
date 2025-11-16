-- V1__create_schema.sql
-- Description: Create core schema tables (users, account_types, accounts)
-- Author: OLTP Demo Team
-- Date: 2025-11-16

-- ============================================================================
-- Table: users
-- Purpose: Represents system users who own accounts and initiate transactions
-- ============================================================================

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

COMMENT ON TABLE users IS 'System users who own accounts and initiate transactions';
COMMENT ON COLUMN users.username IS 'Unique username (3-50 characters)';
COMMENT ON COLUMN users.email IS 'Unique email address (validated format)';
COMMENT ON COLUMN users.version IS 'Optimistic locking version';

-- Indexes for users
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_created_at ON users(created_at);

-- ============================================================================
-- Table: account_types
-- Purpose: Lookup table for account types with business rules
-- ============================================================================

CREATE TABLE account_types (
    id                  SERIAL PRIMARY KEY,
    type_name           VARCHAR(50) NOT NULL UNIQUE,
    min_balance         DECIMAL(15, 2) NOT NULL DEFAULT 0.00,
    description         TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_account_types_min_balance CHECK (min_balance >= 0)
);

COMMENT ON TABLE account_types IS 'Lookup table for account types with minimum balance requirements';
COMMENT ON COLUMN account_types.type_name IS 'Unique account type name (e.g., CHECKING, SAVINGS)';
COMMENT ON COLUMN account_types.min_balance IS 'Minimum required balance for this account type';

-- ============================================================================
-- Table: accounts
-- Purpose: Financial accounts with balances (core entity for ACID demos)
-- ============================================================================

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

COMMENT ON TABLE accounts IS 'Financial accounts with balances - core entity for ACID and concurrency demonstrations';
COMMENT ON COLUMN accounts.version IS 'Optimistic locking version for concurrent updates (JPA @Version)';
COMMENT ON COLUMN accounts.balance IS 'Current account balance (must be non-negative per CHECK constraint)';
COMMENT ON COLUMN accounts.status IS 'Account status: ACTIVE, SUSPENDED, or CLOSED';

-- Indexes for accounts
CREATE INDEX idx_accounts_user_id ON accounts(user_id);
CREATE INDEX idx_accounts_status ON accounts(status);
CREATE INDEX idx_accounts_balance ON accounts(balance);  -- For range queries
CREATE INDEX idx_accounts_updated_at ON accounts(updated_at);

-- ============================================================================
-- Rollback Instructions (for reference, not executed by Flyway)
-- ============================================================================
-- DROP TABLE IF EXISTS accounts CASCADE;
-- DROP TABLE IF EXISTS account_types CASCADE;
-- DROP TABLE IF EXISTS users CASCADE;
