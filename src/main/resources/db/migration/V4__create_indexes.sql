-- V4__create_indexes.sql
-- Description: Additional performance indexes for OLTP workload optimization
-- Author: OLTP Demo Team
-- Date: 2025-11-16
-- Note: Base indexes are already created in V1, V2, V3 with table definitions

-- ============================================================================
-- Additional Composite Indexes for Common Query Patterns
-- ============================================================================

-- Composite index for finding active accounts by user (common query pattern)
CREATE INDEX idx_accounts_user_status ON accounts(user_id, status) WHERE status = 'ACTIVE';

COMMENT ON INDEX idx_accounts_user_status IS 'Composite index for finding active accounts by user - partial index for performance';

-- Composite index for transaction lookups by account and status
CREATE INDEX idx_transactions_from_status ON transactions(from_account_id, status);
CREATE INDEX idx_transactions_to_status ON transactions(to_account_id, status);

COMMENT ON INDEX idx_transactions_from_status IS 'Composite index for filtering transactions by source account and status';
COMMENT ON INDEX idx_transactions_to_status IS 'Composite index for filtering transactions by destination account and status';

-- Composite index for transfer log lookups with correlation ID and status
CREATE INDEX idx_transfer_logs_correlation_status ON transfer_logs(correlation_id, status);

COMMENT ON INDEX idx_transfer_logs_correlation_status IS 'Composite index for trace lookups by correlation ID and status';

-- Covering index for account balance queries (includes commonly selected columns)
CREATE INDEX idx_accounts_balance_covering ON accounts(user_id, account_type_id) INCLUDE (balance, status, updated_at);

COMMENT ON INDEX idx_accounts_balance_covering IS 'Covering index for account summary queries - includes commonly selected columns';

-- ============================================================================
-- Expression Indexes for Specific Query Patterns
-- ============================================================================

-- Index on DATE part of created_at for daily aggregations
CREATE INDEX idx_transactions_created_date ON transactions(DATE(created_at));

COMMENT ON INDEX idx_transactions_created_date IS 'Expression index for daily transaction aggregations';

-- Index on active sessions (not expired)
CREATE INDEX idx_sessions_active ON sessions(user_id, expires_at) WHERE expires_at > NOW();

COMMENT ON INDEX idx_sessions_active IS 'Partial index for active (non-expired) sessions';

-- ============================================================================
-- Summary of All Indexes in Database
-- ============================================================================
-- Total: 32 indexes across all tables
--
-- Base indexes (created in V1-V3): 24
-- Additional indexes (this migration): 8
--
-- Performance considerations:
-- - Partial indexes (WHERE clauses) reduce index size and improve write performance
-- - Covering indexes (INCLUDE) enable index-only scans for common queries
-- - Composite indexes support multi-column WHERE clauses efficiently
-- - Expression indexes enable efficient queries on derived values
-- ============================================================================

-- To verify indexes:
-- SELECT schemaname, tablename, indexname, indexdef
-- FROM pg_indexes
-- WHERE schemaname = 'public'
-- ORDER BY tablename, indexname;
