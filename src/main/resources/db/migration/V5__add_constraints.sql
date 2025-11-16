-- V5__add_constraints.sql
-- Description: Additional business logic and data validation constraints
-- Author: OLTP Demo Team
-- Date: 2025-11-16
-- Note: Base constraints (FK, CHECK, UNIQUE) are already in V1-V3

-- ============================================================================
-- Additional Business Logic Constraints
-- ============================================================================

-- Ensure account balance respects minimum balance requirement from account type
-- This constraint enforces consistency principle at database level
ALTER TABLE accounts
ADD CONSTRAINT chk_accounts_balance_respects_minimum
CHECK (
    balance >= (
        SELECT min_balance
        FROM account_types
        WHERE account_types.id = accounts.account_type_id
    )
);

COMMENT ON CONSTRAINT chk_accounts_balance_respects_minimum ON accounts
IS 'Ensures account balance never falls below the minimum required by account type';

-- ============================================================================
-- Transaction Amount Limits (Business Rules)
-- ============================================================================

-- Maximum transaction amount limit (prevents accidental large transfers)
ALTER TABLE transactions
ADD CONSTRAINT chk_transactions_amount_reasonable
CHECK (amount <= 1000000.00);  -- Max $1M per transaction

COMMENT ON CONSTRAINT chk_transactions_amount_reasonable ON transactions
IS 'Prevents unreasonably large transactions (max $1M) - business rule enforcement';

-- ============================================================================
-- Temporal Constraints
-- ============================================================================

-- Ensure completed_at is after created_at
ALTER TABLE transactions
ADD CONSTRAINT chk_transactions_completed_after_created
CHECK (completed_at IS NULL OR completed_at >= created_at);

COMMENT ON CONSTRAINT chk_transactions_completed_after_created ON transactions
IS 'Ensures transactions cannot complete before they are created (temporal consistency)';

-- Ensure updated_at is at or after created_at for accounts
ALTER TABLE accounts
ADD CONSTRAINT chk_accounts_updated_after_created
CHECK (updated_at >= created_at);

COMMENT ON CONSTRAINT chk_accounts_updated_after_created ON accounts
IS 'Ensures accounts are not updated before creation (temporal consistency)';

-- Ensure updated_at is at or after created_at for users
ALTER TABLE users
ADD CONSTRAINT chk_users_updated_after_created
CHECK (updated_at >= created_at);

COMMENT ON CONSTRAINT chk_users_updated_after_created ON users
IS 'Ensures users are not updated before creation (temporal consistency)';

-- ============================================================================
-- Status Transition Constraints
-- ============================================================================

-- Note: Complex state machine constraints are enforced in application layer
-- Database constraints focus on basic invariants

-- Ensure closed accounts cannot have active transactions
-- (This would require a trigger in practice, documented here for reference)
-- CREATE FUNCTION check_account_not_closed()
-- RETURNS TRIGGER AS $$
-- BEGIN
--     IF EXISTS (
--         SELECT 1 FROM accounts
--         WHERE id IN (NEW.from_account_id, NEW.to_account_id)
--         AND status = 'CLOSED'
--     ) THEN
--         RAISE EXCEPTION 'Cannot create transaction for closed account';
--     END IF;
--     RETURN NEW;
-- END;
-- $$ LANGUAGE plpgsql;
--
-- CREATE TRIGGER trg_check_account_status_before_transaction
-- BEFORE INSERT ON transactions
-- FOR EACH ROW EXECUTE FUNCTION check_account_not_closed();

-- ============================================================================
-- Summary of All Constraints
-- ============================================================================
-- 1. Primary Keys (PK): 6 tables × 1 PK = 6 constraints
-- 2. Foreign Keys (FK): 12 FK constraints
-- 3. CHECK constraints (V1-V3): 15 constraints
-- 4. CHECK constraints (V5 additional): 6 constraints
-- 5. UNIQUE constraints: 7 constraints
-- Total: ~46 constraints
--
-- This aligns with constitution.md principle I: "Data Integrity First"
-- - ACID compliance foundation
-- - Constraint enforcement at database level
-- - Fail-fast on invariant violations
-- ============================================================================

-- To verify all constraints:
-- SELECT conname, contype, pg_get_constraintdef(oid)
-- FROM pg_constraint
-- WHERE connamespace = 'public'::regnamespace
-- ORDER BY conrelid::regclass::text, conname;
