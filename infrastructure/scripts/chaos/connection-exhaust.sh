#!/bin/bash

###############################################################################
# connection-exhaust.sh - Exhausts connection pool for resilience testing
###############################################################################
#
# Purpose: Simulates connection pool exhaustion to test graceful degradation,
#          queueing, and rejection logic.
#
# User Story: US5 - Failure Scenarios and Recovery (T191)
#
# Demonstrations:
# 1. Connection pool exhaustion detection
# 2. Graceful queueing when pool is full
# 3. Request rejection when critically exhausted
# 4. Connection wait time alerting
# 5. Pool recovery after load decrease
#
# Strategy:
#   - Opens N concurrent connections (N > pool max size)
#   - Holds connections open to exhaust pool
#   - Monitors pool metrics during exhaustion
#   - Tests application behavior under exhaustion
#   - Gracefully releases connections
#
# Usage:
#   ./connection-exhaust.sh                    # Use defaults (30 connections)
#   ./connection-exhaust.sh --connections 50   # Open 50 connections
#   ./connection-exhaust.sh --duration 60      # Hold for 60 seconds
#   ./connection-exhaust.sh --release          # Release all held connections
#
# Prerequisites:
#   - psql command-line tool
#   - Database connection credentials
#   - curl for API testing
#
###############################################################################

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
DB_HOST="${POSTGRES_HOST:-localhost}"
DB_PORT="${POSTGRES_PORT:-5432}"
DB_NAME="${POSTGRES_DB:-oltpdb}"
DB_USER="${POSTGRES_USER:-oltp_user}"
DB_PASSWORD="${POSTGRES_PASSWORD:-oltp_password}"

NUM_CONNECTIONS="${NUM_CONNECTIONS:-30}"
DURATION="${DURATION:-30}"
APP_URL="${APP_URL:-http://localhost:8080}"
RELEASE=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --connections)
            NUM_CONNECTIONS="$2"
            shift 2
            ;;
        --duration)
            DURATION="$2"
            shift 2
            ;;
        --release)
            RELEASE=true
            shift
            ;;
        --db-host)
            DB_HOST="$2"
            shift 2
            ;;
        --db-port)
            DB_PORT="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --connections N     Number of connections to open (default: 30)"
            echo "  --duration SECS     How long to hold connections (default: 30)"
            echo "  --release           Release all held connections and exit"
            echo "  --db-host HOST      Database host (default: localhost)"
            echo "  --db-port PORT      Database port (default: 5432)"
            echo "  --help              Show this help message"
            echo ""
            echo "Examples:"
            echo "  $0 --connections 50 --duration 60    # 50 connections for 60s"
            echo "  $0 --release                         # Release all connections"
            exit 0
            ;;
        *)
            echo -e "${RED}Error: Unknown option $1${NC}"
            exit 1
            ;;
    esac
done

###############################################################################
# Helper Functions
###############################################################################

check_prerequisites() {
    if ! command -v psql &> /dev/null; then
        echo -e "${RED}Error: psql command not found${NC}"
        echo "Install: apt-get install postgresql-client"
        exit 1
    fi

    if ! command -v curl &> /dev/null; then
        echo -e "${RED}Error: curl command not found${NC}"
        exit 1
    fi

    # Test database connection
    if ! PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "SELECT 1" &> /dev/null; then
        echo -e "${RED}Error: Cannot connect to database${NC}"
        echo "  Host: $DB_HOST:$DB_PORT"
        echo "  Database: $DB_NAME"
        echo "  User: $DB_USER"
        exit 1
    fi
}

get_pool_stats() {
    echo -e "${BLUE}Current connection pool statistics:${NC}"

    # Get from database
    ACTIVE=$(PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -t -c \
        "SELECT count(*) FROM pg_stat_activity WHERE state = 'active';" 2>/dev/null || echo "0")

    IDLE=$(PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -t -c \
        "SELECT count(*) FROM pg_stat_activity WHERE state = 'idle';" 2>/dev/null || echo "0")

    TOTAL=$(PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -t -c \
        "SELECT count(*) FROM pg_stat_activity;" 2>/dev/null || echo "0")

    echo "  Active connections: $ACTIVE"
    echo "  Idle connections: $IDLE"
    echo "  Total connections: $TOTAL"

    # Try to get from application API
    if curl -s "${APP_URL}/api/metrics" | grep -q "hikaricp_connections_active"; then
        APP_ACTIVE=$(curl -s "${APP_URL}/api/metrics" | grep "hikaricp_connections_active" | awk '{print $2}' | head -1)
        APP_IDLE=$(curl -s "${APP_URL}/api/metrics" | grep "hikaricp_connections_idle" | awk '{print $2}' | head -1)
        APP_PENDING=$(curl -s "${APP_URL}/api/metrics" | grep "hikaricp_connections_pending" | awk '{print $2}' | head -1)

        echo ""
        echo "  Application pool (HikariCP):"
        echo "    Active: $APP_ACTIVE"
        echo "    Idle: $APP_IDLE"
        echo "    Pending threads: $APP_PENDING"
    fi
    echo ""
}

release_connections() {
    echo -e "${YELLOW}Releasing all held connections...${NC}"

    # Kill all idle connections from our user
    KILLED=$(PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -t -c \
        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = '$DB_USER' AND state = 'idle in transaction';" 2>/dev/null | grep -c "t" || echo "0")

    echo -e "${GREEN}✓ Released $KILLED connections${NC}"
}

exhaust_pool() {
    echo -e "${YELLOW}Opening $NUM_CONNECTIONS concurrent connections...${NC}"

    # Create temporary directory for connection PIDs
    CONN_DIR="/tmp/oltp-demo-connections-$$"
    mkdir -p "$CONN_DIR"

    # Open connections in background
    for i in $(seq 1 "$NUM_CONNECTIONS"); do
        {
            # Open connection and hold it with a long-running transaction
            PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
                -c "BEGIN; SELECT pg_sleep($DURATION); COMMIT;" &> /dev/null &

            PID=$!
            echo "$PID" > "$CONN_DIR/conn_$i.pid"

            # Progress indicator
            if [ $((i % 5)) -eq 0 ]; then
                echo -n "."
            fi
        } &
    done

    echo ""
    echo -e "${GREEN}✓ Opened $NUM_CONNECTIONS connections${NC}"
    echo ""

    # Wait a bit for connections to establish
    sleep 2

    # Monitor pool exhaustion
    echo -e "${YELLOW}Monitoring pool exhaustion for ${DURATION}s...${NC}"
    echo ""

    for t in $(seq 1 $((DURATION / 5))); do
        echo -e "${BLUE}=== T+$((t * 5))s ===${NC}"
        get_pool_stats

        # Test application behavior
        echo -e "${YELLOW}Testing application behavior under exhaustion...${NC}"

        HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
            -X GET "${APP_URL}/api/health" || echo "000")

        if [ "$HTTP_CODE" = "200" ]; then
            echo -e "${GREEN}✓ Health check succeeded (HTTP $HTTP_CODE)${NC}"
        elif [ "$HTTP_CODE" = "503" ]; then
            echo -e "${YELLOW}⚠ Service unavailable (HTTP $HTTP_CODE) - Pool exhausted${NC}"
        else
            echo -e "${RED}✗ Health check failed (HTTP $HTTP_CODE)${NC}"
        fi
        echo ""

        sleep 5
    done

    # Cleanup
    echo -e "${YELLOW}Cleaning up connections...${NC}"
    for pidfile in "$CONN_DIR"/*.pid; do
        if [ -f "$pidfile" ]; then
            PID=$(cat "$pidfile")
            kill "$PID" 2>/dev/null || true
        fi
    done
    rm -rf "$CONN_DIR"

    echo -e "${GREEN}✓ Connections released${NC}"
}

###############################################################################
# Main Operations
###############################################################################

echo "========================================================================="
echo "  Connection Pool Exhaustion - Chaos Engineering"
echo "========================================================================="
echo ""
echo "Target Database: $DB_HOST:$DB_PORT/$DB_NAME"
echo "Connections to open: $NUM_CONNECTIONS"
echo "Duration: ${DURATION}s"
echo ""

# Check prerequisites
check_prerequisites

# Show initial state
echo -e "${YELLOW}Initial state:${NC}"
get_pool_stats

if [ "$RELEASE" = true ]; then
    # Release connections only
    release_connections
    get_pool_stats
else
    # Exhaust pool
    exhaust_pool

    # Show final state
    echo -e "${YELLOW}Final state after exhaustion:${NC}"
    get_pool_stats
fi

echo "========================================================================="
echo "  Next Steps"
echo "========================================================================="
echo ""

if [ "$RELEASE" = true ]; then
    echo "Connections released. Pool should be back to normal."
    echo ""
    echo "  Verify pool health:"
    echo "    curl http://localhost:8080/api/health"
else
    echo "Pool exhaustion test complete. Observe the following:"
    echo ""
    echo "  1. Check application logs for pool exhaustion warnings:"
    echo "     docker logs oltp-demo-app | grep -i 'pool'"
    echo ""
    echo "  2. View connection pool metrics:"
    echo "     curl http://localhost:8080/api/metrics | grep hikaricp"
    echo ""
    echo "  3. Check for pending threads:"
    echo "     curl http://localhost:8080/api/metrics | grep pending"
    echo ""
    echo "  4. Test retry behavior under exhaustion:"
    echo "     curl -X POST http://localhost:8080/api/demos/failure/retry \\"
    echo "       -H 'Content-Type: application/json' \\"
    echo "       -d '{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":100,\"simulateFailure\":false}'"
fi
echo ""
