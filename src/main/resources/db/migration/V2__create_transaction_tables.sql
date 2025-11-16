-- V2__create_transaction_tables.sql
-- Description: Create transaction and transfer_logs tables
-- Author: OLTP Demo Team
-- Date: 2025-11-16

-- ============================================================================
-- Table: transactions
-- Purpose: Business transactions (transfers, deposits, withdrawals)
-- Central to ACID demonstrations
-- ============================================================================

CREATE TABLE transactions (
    id                  BIGSERIAL PRIMARY KEY,
    from_account_id     BIGINT,  -- NULL for deposits
    to_account_id       BIGINT,  -- NULL for withdrawals
    amount              DECIMAL(15, 2) NOT NULL,
    transaction_type    VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    correlation_id      UUID NOT NULL,  -- For distributed tracing
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

COMMENT ON TABLE transactions IS 'Business transactions (transfers, deposits, withdrawals) - central to ACID demonstrations';
COMMENT ON COLUMN transactions.correlation_id IS 'UUID for distributed tracing across logs, metrics, and traces';
COMMENT ON COLUMN transactions.status IS 'Transaction lifecycle: PENDING → COMPLETED/FAILED/ROLLED_BACK';
COMMENT ON COLUMN transactions.from_account_id IS 'Source account (NULL for deposits)';
COMMENT ON COLUMN transactions.to_account_id IS 'Destination account (NULL for withdrawals)';

-- Indexes for transactions
CREATE INDEX idx_transactions_from_account ON transactions(from_account_id);
CREATE INDEX idx_transactions_to_account ON transactions(to_account_id);
CREATE INDEX idx_transactions_correlation_id ON transactions(correlation_id);
CREATE INDEX idx_transactions_status ON transactions(status);
CREATE INDEX idx_transactions_created_at ON transactions(created_at DESC);  -- Recent first
CREATE INDEX idx_transactions_completed_at ON transactions(completed_at DESC) WHERE completed_at IS NOT NULL;  -- Partial index

-- ============================================================================
-- Table: transfer_logs
-- Purpose: Immutable audit trail for transfer operations (durability demo)
-- ============================================================================

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

COMMENT ON TABLE transfer_logs IS 'Immutable audit trail for transfer operations - demonstrates durability and WAL concepts';
COMMENT ON COLUMN transfer_logs.logged_at IS 'Timestamp when log entry was created (append-only, never updated)';

-- Indexes for transfer_logs
CREATE INDEX idx_transfer_logs_transaction_id ON transfer_logs(transaction_id);
CREATE INDEX idx_transfer_logs_from_account ON transfer_logs(from_account_id);
CREATE INDEX idx_transfer_logs_to_account ON transfer_logs(to_account_id);
CREATE INDEX idx_transfer_logs_correlation_id ON transfer_logs(correlation_id);
CREATE INDEX idx_transfer_logs_logged_at ON transfer_logs(logged_at DESC);

-- ============================================================================
-- Rollback Instructions (for reference)
-- ============================================================================
-- DROP TABLE IF EXISTS transfer_logs CASCADE;
-- DROP TABLE IF EXISTS transactions CASCADE;
