#!/bin/bash

# OLTP Demo - Generate Realistic Test Data
# Usage: ./infrastructure/scripts/seed-data.sh [row_count]
# Default: 1,000,000 rows

set -e

# Configuration
DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-5432}
DB_NAME=${DB_NAME:-oltpdemo}
DB_USER=${DB_USER:-postgres}
DB_PASSWORD=${DB_PASSWORD:-postgres}

# Target row count (default: 1M)
TARGET_ROWS=${1:-1000000}

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "🌱 OLTP Demo - Seed Test Data"
echo "=============================="
echo ""
echo "Target rows: $(printf "%'d" $TARGET_ROWS)"
echo "Database: $DB_NAME@$DB_HOST:$DB_PORT"
echo ""

# Function to execute SQL
execute_sql() {
    PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "$1" -t -A
}

# Check if database is accessible
echo -e "${BLUE}▶ Checking database connection...${NC}"
if execute_sql "SELECT 1" &> /dev/null; then
    echo -e "${GREEN}✓ Database connection successful${NC}"
else
    echo -e "${RED}✗ Cannot connect to database${NC}"
    exit 1
fi

# Check if data already exists
EXISTING_USERS=$(execute_sql "SELECT COUNT(*) FROM users")
EXISTING_ACCOUNTS=$(execute_sql "SELECT COUNT(*) FROM accounts")
EXISTING_TRANSACTIONS=$(execute_sql "SELECT COUNT(*) FROM transactions")

if [ "$EXISTING_USERS" -gt 100 ]; then
    echo -e "${YELLOW}⚠ Data already exists (${EXISTING_USERS} users, ${EXISTING_ACCOUNTS} accounts, ${EXISTING_TRANSACTIONS} transactions)${NC}"
    read -p "Do you want to clear existing data and reseed? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Aborted."
        exit 0
    fi

    echo -e "${BLUE}▶ Clearing existing data...${NC}"
    execute_sql "TRUNCATE TABLE transfer_logs CASCADE"
    execute_sql "TRUNCATE TABLE transactions CASCADE"
    execute_sql "TRUNCATE TABLE accounts CASCADE"
    execute_sql "TRUNCATE TABLE users CASCADE"
    execute_sql "TRUNCATE TABLE sessions CASCADE"
    echo -e "${GREEN}✓ Data cleared${NC}"
fi

# Calculate distribution
NUM_USERS=$(($TARGET_ROWS / 10))      # 10% of target rows
NUM_ACCOUNTS=$(($TARGET_ROWS / 5))    # 20% of target rows
NUM_TRANSACTIONS=$TARGET_ROWS         # 100% of target rows

echo ""
echo "Data distribution:"
echo "  Users:        $(printf "%'d" $NUM_USERS)"
echo "  Accounts:     $(printf "%'d" $NUM_ACCOUNTS)"
echo "  Transactions: $(printf "%'d" $NUM_TRANSACTIONS)"
echo ""

# Seed users
echo -e "${BLUE}▶ Generating users...${NC}"
BATCH_SIZE=10000
for ((i=1; i<=$NUM_USERS; i+=$BATCH_SIZE)); do
    END=$(($i + $BATCH_SIZE - 1))
    if [ $END -gt $NUM_USERS ]; then
        END=$NUM_USERS
    fi

    execute_sql "
    INSERT INTO users (email, status, created_at, updated_at)
    SELECT
        'user' || n || '@example.com',
        CASE (RANDOM() * 100)::INT % 100
            WHEN 0 THEN 'INACTIVE'
            WHEN 1 THEN 'SUSPENDED'
            ELSE 'ACTIVE'
        END,
        NOW() - (RANDOM() * INTERVAL '365 days'),
        NOW() - (RANDOM() * INTERVAL '30 days')
    FROM generate_series($i, $END) AS n
    " > /dev/null

    echo -ne "\r  Progress: $(($i * 100 / $NUM_USERS))%"
done
echo -e "\r${GREEN}✓ Generated $(printf "%'d" $NUM_USERS) users${NC}"

# Seed accounts
echo -e "${BLUE}▶ Generating accounts...${NC}"
for ((i=1; i<=$NUM_ACCOUNTS; i+=$BATCH_SIZE)); do
    END=$(($i + $BATCH_SIZE - 1))
    if [ $END -gt $NUM_ACCOUNTS ]; then
        END=$NUM_ACCOUNTS
    fi

    execute_sql "
    INSERT INTO accounts (user_id, account_type_id, account_number, balance, status, version, created_at, updated_at)
    SELECT
        (RANDOM() * $NUM_USERS)::INT + 1,
        (RANDOM() * 3)::INT + 1,
        'ACC' || LPAD(n::TEXT, 12, '0'),
        (RANDOM() * 100000)::NUMERIC(15,2),
        CASE (RANDOM() * 100)::INT % 100
            WHEN 0 THEN 'INACTIVE'
            WHEN 1 THEN 'FROZEN'
            WHEN 2 THEN 'CLOSED'
            ELSE 'ACTIVE'
        END,
        0,
        NOW() - (RANDOM() * INTERVAL '365 days'),
        NOW() - (RANDOM() * INTERVAL '30 days')
    FROM generate_series($i, $END) AS n
    " > /dev/null

    echo -ne "\r  Progress: $(($i * 100 / $NUM_ACCOUNTS))%"
done
echo -e "\r${GREEN}✓ Generated $(printf "%'d" $NUM_ACCOUNTS) accounts${NC}"

# Seed transactions
echo -e "${BLUE}▶ Generating transactions...${NC}"
for ((i=1; i<=$NUM_TRANSACTIONS; i+=$BATCH_SIZE)); do
    END=$(($i + $BATCH_SIZE - 1))
    if [ $END -gt $NUM_TRANSACTIONS ]; then
        END=$NUM_TRANSACTIONS
    fi

    execute_sql "
    INSERT INTO transactions (from_account_id, to_account_id, amount, status, correlation_id, created_at, updated_at)
    SELECT
        (RANDOM() * $NUM_ACCOUNTS)::INT + 1,
        (RANDOM() * $NUM_ACCOUNTS)::INT + 1,
        (RANDOM() * 10000 + 10)::NUMERIC(15,2),
        CASE (RANDOM() * 100)::INT % 100
            WHEN 0 THEN 'PENDING'
            WHEN 1 THEN 'FAILED'
            ELSE 'SUCCESS'
        END,
        'SEED-' || MD5(RANDOM()::TEXT),
        NOW() - (RANDOM() * INTERVAL '365 days'),
        NOW() - (RANDOM() * INTERVAL '365 days')
    FROM generate_series($i, $END) AS n
    WHERE (RANDOM() * $NUM_ACCOUNTS)::INT + 1 <> (RANDOM() * $NUM_ACCOUNTS)::INT + 1
    " > /dev/null

    echo -ne "\r  Progress: $(($i * 100 / $NUM_TRANSACTIONS))%"
done
echo -e "\r${GREEN}✓ Generated $(printf "%'d" $NUM_TRANSACTIONS) transactions${NC}"

# Seed transfer logs (audit trail)
echo -e "${BLUE}▶ Generating transfer logs...${NC}"
NUM_LOGS=$(($NUM_TRANSACTIONS / 2))
for ((i=1; i<=$NUM_LOGS; i+=$BATCH_SIZE)); do
    END=$(($i + $BATCH_SIZE - 1))
    if [ $END -gt $NUM_LOGS ]; then
        END=$NUM_LOGS
    fi

    execute_sql "
    INSERT INTO transfer_logs (transaction_id, from_account_balance_before, from_account_balance_after, to_account_balance_before, to_account_balance_after, created_at)
    SELECT
        (RANDOM() * $NUM_TRANSACTIONS)::INT + 1,
        (RANDOM() * 100000)::NUMERIC(15,2),
        (RANDOM() * 100000)::NUMERIC(15,2),
        (RANDOM() * 100000)::NUMERIC(15,2),
        (RANDOM() * 100000)::NUMERIC(15,2),
        NOW() - (RANDOM() * INTERVAL '365 days')
    FROM generate_series($i, $END) AS n
    " > /dev/null

    echo -ne "\r  Progress: $(($i * 100 / $NUM_LOGS))%"
done
echo -e "\r${GREEN}✓ Generated $(printf "%'d" $NUM_LOGS) transfer logs${NC}"

# Vacuum and analyze
echo -e "${BLUE}▶ Optimizing database...${NC}"
execute_sql "VACUUM ANALYZE users" > /dev/null
execute_sql "VACUUM ANALYZE accounts" > /dev/null
execute_sql "VACUUM ANALYZE transactions" > /dev/null
execute_sql "VACUUM ANALYZE transfer_logs" > /dev/null
echo -e "${GREEN}✓ Database optimized${NC}"

# Print statistics
echo ""
echo -e "${GREEN}✓ Data seeding completed successfully!${NC}"
echo ""
echo "Final statistics:"
execute_sql "
SELECT
    'Users' AS table_name,
    COUNT(*)::TEXT AS row_count,
    pg_size_pretty(pg_total_relation_size('users')) AS size
FROM users
UNION ALL
SELECT
    'Accounts',
    COUNT(*)::TEXT,
    pg_size_pretty(pg_total_relation_size('accounts'))
FROM accounts
UNION ALL
SELECT
    'Transactions',
    COUNT(*)::TEXT,
    pg_size_pretty(pg_total_relation_size('transactions'))
FROM transactions
UNION ALL
SELECT
    'Transfer Logs',
    COUNT(*)::TEXT,
    pg_size_pretty(pg_total_relation_size('transfer_logs'))
FROM transfer_logs
" | column -t -s '|'

echo ""
echo "Database is ready for demonstrations and load testing!"
