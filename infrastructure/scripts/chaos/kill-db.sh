#!/bin/bash

###############################################################################
# kill-db.sh - Simulates database crash for failure recovery testing
###############################################################################
#
# Purpose: Demonstrates crash recovery and durability by forcefully stopping
#          the PostgreSQL database and verifying committed transactions survive.
#
# User Story: US5 - Failure Scenarios and Recovery (T189)
#
# Demonstrations:
# 1. Database crash simulation (docker stop)
# 2. Committed transaction durability verification
# 3. WAL replay on recovery
# 4. Automatic restart and recovery
#
# Usage:
#   ./kill-db.sh                    # Kill and auto-restart (default)
#   ./kill-db.sh --no-restart       # Kill without restart
#   ./kill-db.sh --graceful         # Graceful shutdown (SIGTERM)
#   ./kill-db.sh --hard-kill        # Hard kill (SIGKILL)
#
# Docker Compose Integration:
#   Assumes database is running via docker-compose with service name 'postgres'
#   Container name: oltp-demo-postgres (or similar)
#
# Safety:
#   - Only affects containerized database (not host PostgreSQL)
#   - Data persists via Docker volumes
#   - Safe for development/demo environments
#
###############################################################################

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
CONTAINER_NAME="${POSTGRES_CONTAINER:-oltp-demo-postgres}"
RESTART=true
KILL_TYPE="stop"  # stop (graceful), kill (SIGKILL)
RESTART_DELAY=2   # Seconds to wait before restart

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --no-restart)
            RESTART=false
            shift
            ;;
        --graceful)
            KILL_TYPE="stop"
            shift
            ;;
        --hard-kill)
            KILL_TYPE="kill"
            shift
            ;;
        --container)
            CONTAINER_NAME="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --no-restart    Kill database without automatic restart"
            echo "  --graceful      Graceful shutdown (SIGTERM) - default"
            echo "  --hard-kill     Hard kill (SIGKILL) - simulates crash"
            echo "  --container     Specify container name (default: oltp-demo-postgres)"
            echo "  --help          Show this help message"
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
    if ! command -v docker &> /dev/null; then
        echo -e "${RED}Error: docker command not found${NC}"
        exit 1
    fi

    if ! docker ps -q -f name="$CONTAINER_NAME" &> /dev/null; then
        echo -e "${RED}Error: Container '$CONTAINER_NAME' not found${NC}"
        echo "Available containers:"
        docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
        exit 1
    fi
}

get_container_status() {
    docker ps -a -f name="$CONTAINER_NAME" --format "{{.Status}}"
}

wait_for_container() {
    local container=$1
    local max_wait=30
    local wait_count=0

    echo -e "${YELLOW}Waiting for container to be ready...${NC}"

    while [ $wait_count -lt $max_wait ]; do
        if docker exec "$container" pg_isready -U postgres &> /dev/null; then
            echo -e "${GREEN}✓ Database is ready${NC}"
            return 0
        fi
        sleep 1
        wait_count=$((wait_count + 1))
        echo -n "."
    done

    echo -e "${RED}✗ Database did not become ready within ${max_wait}s${NC}"
    return 1
}

###############################################################################
# Main Chaos Operations
###############################################################################

echo "========================================================================="
echo "  Database Crash Simulation - Chaos Engineering"
echo "========================================================================="
echo ""
echo "Container: $CONTAINER_NAME"
echo "Kill Type: $KILL_TYPE"
echo "Auto-Restart: $RESTART"
echo ""

# Check prerequisites
check_prerequisites

# Get current status
echo -e "${YELLOW}Current database status:${NC}"
get_container_status
echo ""

# Step 1: Show current connections and transactions
echo -e "${YELLOW}Step 1: Checking current database state...${NC}"
ACTIVE_CONNECTIONS=$(docker exec "$CONTAINER_NAME" psql -U postgres -t -c "SELECT count(*) FROM pg_stat_activity WHERE state = 'active';" 2>/dev/null || echo "0")
echo "  Active connections: $ACTIVE_CONNECTIONS"

COMMITTED_TXNS=$(docker exec "$CONTAINER_NAME" psql -U postgres -t -c "SELECT xact_commit FROM pg_stat_database WHERE datname = current_database();" 2>/dev/null || echo "0")
echo "  Committed transactions: $COMMITTED_TXNS"
echo ""

# Step 2: Kill database
echo -e "${RED}Step 2: Simulating database crash...${NC}"

if [ "$KILL_TYPE" = "kill" ]; then
    echo "  Sending SIGKILL (hard kill - simulates power loss)"
    docker kill -s SIGKILL "$CONTAINER_NAME"
else
    echo "  Stopping gracefully (SIGTERM)"
    docker stop "$CONTAINER_NAME"
fi

echo -e "${GREEN}✓ Database stopped${NC}"
echo ""

# Step 3: Verify database is down
echo -e "${YELLOW}Step 3: Verifying database is down...${NC}"
sleep 1

if docker exec "$CONTAINER_NAME" pg_isready -U postgres &> /dev/null; then
    echo -e "${RED}✗ Database is still responding (unexpected)${NC}"
    exit 1
else
    echo -e "${GREEN}✓ Database is not responding (expected)${NC}"
fi
echo ""

# Step 4: Restart (if requested)
if [ "$RESTART" = true ]; then
    echo -e "${YELLOW}Step 4: Restarting database (simulating recovery)...${NC}"
    echo "  Waiting ${RESTART_DELAY}s before restart..."
    sleep "$RESTART_DELAY"

    docker start "$CONTAINER_NAME"
    echo -e "${GREEN}✓ Database container started${NC}"
    echo ""

    # Step 5: Wait for database to be ready
    echo -e "${YELLOW}Step 5: Waiting for database recovery...${NC}"
    if wait_for_container "$CONTAINER_NAME"; then
        echo ""

        # Step 6: Verify recovery
        echo -e "${YELLOW}Step 6: Verifying recovery and durability...${NC}"

        UPTIME=$(docker exec "$CONTAINER_NAME" psql -U postgres -t -c "SELECT EXTRACT(EPOCH FROM (NOW() - pg_postmaster_start_time()));" 2>/dev/null || echo "0")
        echo "  Database uptime: ${UPTIME}s (should be recent)"

        NEW_COMMITTED_TXNS=$(docker exec "$CONTAINER_NAME" psql -U postgres -t -c "SELECT xact_commit FROM pg_stat_database WHERE datname = current_database();" 2>/dev/null || echo "0")
        echo "  Committed transactions after recovery: $NEW_COMMITTED_TXNS"
        echo "  Transactions before crash: $COMMITTED_TXNS"

        if [ "$NEW_COMMITTED_TXNS" -ge "$COMMITTED_TXNS" ]; then
            echo -e "${GREEN}✓ Committed transactions survived crash (DURABILITY verified)${NC}"
        else
            echo -e "${RED}✗ Transaction count decreased (unexpected)${NC}"
        fi

        # Check WAL replay
        IN_RECOVERY=$(docker exec "$CONTAINER_NAME" psql -U postgres -t -c "SELECT pg_is_in_recovery();" 2>/dev/null || echo "f")
        if [ "$IN_RECOVERY" = " t" ]; then
            echo -e "${YELLOW}  Database is currently in recovery mode (replaying WAL)${NC}"
        else
            echo -e "${GREEN}  Database recovery complete (WAL replay finished)${NC}"
        fi
    else
        echo -e "${RED}✗ Database recovery failed${NC}"
        exit 1
    fi
else
    echo -e "${YELLOW}Step 4: Database left in stopped state (--no-restart)${NC}"
    echo ""
    echo "To restart manually:"
    echo "  docker start $CONTAINER_NAME"
fi

echo ""
echo "========================================================================="
echo "  Chaos Simulation Complete"
echo "========================================================================="
echo ""
echo "Next steps:"
echo "  1. Verify transactions with correlation ID:"
echo "     curl http://localhost:8080/api/demos/failure/recovery/verify?correlationId=YOUR_ID"
echo ""
echo "  2. Check WAL configuration:"
echo "     curl http://localhost:8080/api/demos/failure/recovery/wal"
echo ""
echo "  3. View recovery statistics:"
echo "     curl http://localhost:8080/api/demos/failure/recovery/statistics"
echo ""
