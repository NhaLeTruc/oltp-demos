#!/bin/bash

# OLTP Demo - Clean Reset
# Usage: ./infrastructure/scripts/clean-reset.sh [--force]
# Resets database, caches, and all data to initial state

set -e

# Configuration
FORCE_MODE=${1}

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo "🧹 OLTP Demo - Clean Reset"
echo "=========================="
echo ""

# Safety check
if [ "$FORCE_MODE" != "--force" ]; then
    echo -e "${YELLOW}⚠ WARNING: This will:${NC}"
    echo "  - Stop all Docker containers"
    echo "  - Remove all volumes (database data will be lost)"
    echo "  - Clear Redis cache"
    echo "  - Reset application state"
    echo ""
    read -p "Are you sure you want to continue? (yes/NO) " -r
    echo
    if [[ ! $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
        echo "Aborted."
        exit 0
    fi
fi

echo -e "${BLUE}▶ Stopping Docker containers...${NC}"
cd infrastructure/docker
docker-compose down -v
cd ../..
echo -e "${GREEN}✓ Containers stopped and volumes removed${NC}"

echo -e "${BLUE}▶ Cleaning Maven build artifacts...${NC}"
if [ -d "target" ]; then
    rm -rf target/
    echo -e "${GREEN}✓ Maven target directory removed${NC}"
else
    echo -e "${YELLOW}  (no target directory found)${NC}"
fi

echo -e "${BLUE}▶ Cleaning test results...${NC}"
if [ -d "test-output" ]; then
    rm -rf test-output/
    echo -e "${GREEN}✓ Test output removed${NC}"
fi

if [ -d "benchmark-results" ]; then
    rm -rf benchmark-results/
    echo -e "${GREEN}✓ Benchmark results removed${NC}"
fi

echo -e "${BLUE}▶ Cleaning logs...${NC}"
if [ -d "logs" ]; then
    rm -rf logs/
    echo -e "${GREEN}✓ Logs removed${NC}"
fi

find . -name "*.log" -type f -delete 2>/dev/null || true
echo -e "${GREEN}✓ Log files removed${NC}"

echo -e "${BLUE}▶ Cleaning temporary files...${NC}"
find . -name "*.tmp" -type f -delete 2>/dev/null || true
find . -name "*.bak" -type f -delete 2>/dev/null || true
find . -name "*~" -type f -delete 2>/dev/null || true
echo -e "${GREEN}✓ Temporary files removed${NC}"

echo -e "${BLUE}▶ Starting fresh Docker containers...${NC}"
cd infrastructure/docker
docker-compose up -d
cd ../..

# Wait for PostgreSQL
echo -e "${BLUE}▶ Waiting for PostgreSQL...${NC}"
MAX_RETRIES=30
RETRY_COUNT=0
until docker-compose -f infrastructure/docker/docker-compose.yml exec -T postgres pg_isready -U postgres &> /dev/null; do
    RETRY_COUNT=$((RETRY_COUNT+1))
    if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
        echo -e "${RED}✗ PostgreSQL failed to start${NC}"
        exit 1
    fi
    echo -n "."
    sleep 1
done
echo ""
echo -e "${GREEN}✓ PostgreSQL is ready${NC}"

# Wait for Redis
echo -e "${BLUE}▶ Waiting for Redis...${NC}"
RETRY_COUNT=0
until docker-compose -f infrastructure/docker/docker-compose.yml exec -T redis redis-cli ping | grep -q PONG &> /dev/null; do
    RETRY_COUNT=$((RETRY_COUNT+1))
    if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
        echo -e "${RED}✗ Redis failed to start${NC}"
        exit 1
    fi
    echo -n "."
    sleep 1
done
echo ""
echo -e "${GREEN}✓ Redis is ready${NC}"

echo -e "${BLUE}▶ Running Flyway migrations...${NC}"
if [ -f "./mvnw" ]; then
    ./mvnw flyway:clean flyway:migrate
else
    mvn flyway:clean flyway:migrate
fi
echo -e "${GREEN}✓ Database schema recreated${NC}"

echo ""
echo -e "${GREEN}✓ Clean reset completed successfully!${NC}"
echo ""
echo "Next steps:"
echo "  1. Seed test data (optional):"
echo "     ${GREEN}./infrastructure/scripts/seed-data.sh${NC}"
echo ""
echo "  2. Start the application:"
echo "     ${GREEN}./mvnw spring-boot:run${NC}"
echo ""
echo "  3. Or run full setup:"
echo "     ${GREEN}./infrastructure/scripts/setup.sh${NC}"
echo ""
