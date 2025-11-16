-- V6__seed_reference_data.sql
-- Description: Seed reference data for account types and demo users/accounts
-- Author: OLTP Demo Team
-- Date: 2025-11-16

-- ============================================================================
-- Account Types (Reference Data)
-- ============================================================================

INSERT INTO account_types (type_name, min_balance, description) VALUES
    ('CHECKING', 0.00, 'Standard checking account with no minimum balance requirement'),
    ('SAVINGS', 100.00, 'Savings account with $100 minimum balance requirement'),
    ('BUSINESS', 1000.00, 'Business account with $1000 minimum balance requirement'),
    ('PREMIUM', 5000.00, 'Premium account with $5000 minimum balance and enhanced features')
ON CONFLICT (type_name) DO NOTHING;

COMMENT ON TABLE account_types IS 'Reference data for account types - rarely changes, good candidate for caching';

-- ============================================================================
-- Demo Users (for testing and demonstrations)
-- ============================================================================

INSERT INTO users (username, email, full_name, created_at, updated_at) VALUES
    ('alice', 'alice@example.com', 'Alice Anderson', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('bob', 'bob@example.com', 'Bob Brown', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('charlie', 'charlie@example.com', 'Charlie Chen', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('diana', 'diana@example.com', 'Diana Davis', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('eve', 'eve@example.com', 'Eve Evans', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (username) DO NOTHING;

-- ============================================================================
-- Demo Accounts (for ACID and concurrency demonstrations)
-- ============================================================================

-- Alice's accounts (CHECKING and SAVINGS)
INSERT INTO accounts (user_id, account_type_id, balance, status, created_at, updated_at)
SELECT
    u.id,
    at.id,
    CASE at.type_name
        WHEN 'CHECKING' THEN 1000.00
        WHEN 'SAVINGS' THEN 5000.00
    END,
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM users u
CROSS JOIN account_types at
WHERE u.username = 'alice'
  AND at.type_name IN ('CHECKING', 'SAVINGS')
ON CONFLICT DO NOTHING;

-- Bob's accounts (CHECKING and BUSINESS)
INSERT INTO accounts (user_id, account_type_id, balance, status, created_at, updated_at)
SELECT
    u.id,
    at.id,
    CASE at.type_name
        WHEN 'CHECKING' THEN 2000.00
        WHEN 'BUSINESS' THEN 10000.00
    END,
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM users u
CROSS JOIN account_types at
WHERE u.username = 'bob'
  AND at.type_name IN ('CHECKING', 'BUSINESS')
ON CONFLICT DO NOTHING;

-- Charlie's accounts (SAVINGS and PREMIUM)
INSERT INTO accounts (user_id, account_type_id, balance, status, created_at, updated_at)
SELECT
    u.id,
    at.id,
    CASE at.type_name
        WHEN 'SAVINGS' THEN 3000.00
        WHEN 'PREMIUM' THEN 15000.00
    END,
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM users u
CROSS JOIN account_types at
WHERE u.username = 'charlie'
  AND at.type_name IN ('SAVINGS', 'PREMIUM')
ON CONFLICT DO NOTHING;

-- Diana's accounts (CHECKING only, for simple demos)
INSERT INTO accounts (user_id, account_type_id, balance, status, created_at, updated_at)
SELECT
    u.id,
    at.id,
    500.00,
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM users u
CROSS JOIN account_types at
WHERE u.username = 'diana'
  AND at.type_name = 'CHECKING'
ON CONFLICT DO NOTHING;

-- Eve's accounts (multiple CHECKING accounts for concurrency demos)
INSERT INTO accounts (user_id, account_type_id, balance, status, created_at, updated_at)
SELECT
    u.id,
    at.id,
    1500.00,
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM users u
CROSS JOIN account_types at
WHERE u.username = 'eve'
  AND at.type_name = 'CHECKING'
ON CONFLICT DO NOTHING;

-- ============================================================================
-- Verification Queries (for post-migration validation)
-- ============================================================================

-- Verify account types
DO $$
DECLARE
    account_type_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO account_type_count FROM account_types;
    IF account_type_count < 4 THEN
        RAISE WARNING 'Expected at least 4 account types, found %', account_type_count;
    ELSE
        RAISE NOTICE 'Successfully seeded % account types', account_type_count;
    END IF;
END $$;

-- Verify demo users
DO $$
DECLARE
    user_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO user_count FROM users;
    IF user_count < 5 THEN
        RAISE WARNING 'Expected at least 5 demo users, found %', user_count;
    ELSE
        RAISE NOTICE 'Successfully seeded % demo users', user_count;
    END IF;
END $$;

-- Verify demo accounts
DO $$
DECLARE
    account_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO account_count FROM accounts;
    IF account_count < 7 THEN
        RAISE WARNING 'Expected at least 7 demo accounts, found %', account_count;
    ELSE
        RAISE NOTICE 'Successfully seeded % demo accounts', account_count;
    END IF;
END $$;

-- Display seed data summary
SELECT
    'Account Types' AS category,
    COUNT(*)::TEXT AS count
FROM account_types
UNION ALL
SELECT
    'Users' AS category,
    COUNT(*)::TEXT AS count
FROM users
UNION ALL
SELECT
    'Accounts' AS category,
    COUNT(*)::TEXT AS count
FROM accounts;

-- ============================================================================
-- Notes
-- ============================================================================
-- 1. ON CONFLICT DO NOTHING ensures idempotent migrations
-- 2. Reference data (account_types) should be cached in application
-- 3. Demo data is for testing only, not for production use
-- 4. Total demo account balance: ~$38,000
-- ============================================================================
