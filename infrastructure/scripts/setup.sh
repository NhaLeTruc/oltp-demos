#!/bin/bash

# OLTP Demo - One-Command Project Initialization
# Usage: ./infrastructure/scripts/setup.sh

set -e

echo "🚀 OLTP Core Capabilities Demo - Project Setup"
echo "=============================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Function to print step
print_step() {
    echo -e "${BLUE}▶ $1${NC}"
}

# Function to print success
print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

# Function to print warning
print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

# Function to print error
print_error() {
    echo -e "${RED}✗ $1${NC}"
}

# Check prerequisites
print_step "Checking prerequisites..."

# Check Java
if command -v java &> /dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -ge "17" ]; then
        print_success "Java $JAVA_VERSION found"
    else
        print_error "Java 17+ required, found version $JAVA_VERSION"
        exit 1
    fi
else
    print_error "Java not found. Please install Java 17+"
    exit 1
fi

# Check Docker
if command -v docker &> /dev/null; then
    print_success "Docker found"
else
    print_error "Docker not found. Please install Docker"
    exit 1
fi

# Check Docker Compose
if command -v docker-compose &> /dev/null || docker compose version &> /dev/null; then
    print_success "Docker Compose found"
else
    print_error "Docker Compose not found. Please install Docker Compose"
    exit 1
fi

# Check Maven (or use wrapper)
if [ -f "./mvnw" ]; then
    MAVEN_CMD="./mvnw"
    print_success "Maven wrapper found"
elif command -v mvn &> /dev/null; then
    MAVEN_CMD="mvn"
    print_success "Maven found"
else
    print_error "Maven not found and no wrapper available"
    exit 1
fi

echo ""
print_step "Starting infrastructure services..."

# Start Docker Compose services
cd infrastructure/docker
docker-compose down -v 2>/dev/null || true
docker-compose up -d
cd ../..

# Wait for PostgreSQL to be ready
print_step "Waiting for PostgreSQL to be ready..."
MAX_RETRIES=30
RETRY_COUNT=0
until docker-compose -f infrastructure/docker/docker-compose.yml exec -T postgres pg_isready -U postgres &> /dev/null; do
    RETRY_COUNT=$((RETRY_COUNT+1))
    if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
        print_error "PostgreSQL failed to start after $MAX_RETRIES attempts"
        exit 1
    fi
    echo -n "."
    sleep 1
done
echo ""
print_success "PostgreSQL is ready"

# Wait for Redis
print_step "Waiting for Redis to be ready..."
RETRY_COUNT=0
until docker-compose -f infrastructure/docker/docker-compose.yml exec -T redis redis-cli ping | grep -q PONG &> /dev/null; do
    RETRY_COUNT=$((RETRY_COUNT+1))
    if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
        print_error "Redis failed to start after $MAX_RETRIES attempts"
        exit 1
    fi
    echo -n "."
    sleep 1
done
echo ""
print_success "Redis is ready"

echo ""
print_step "Building application..."

# Clean and build with Maven
$MAVEN_CMD clean install -DskipTests

print_success "Application built successfully"

echo ""
print_step "Running database migrations..."

# Run Flyway migrations
$MAVEN_CMD flyway:migrate

print_success "Database migrations completed"

echo ""
print_step "Seeding test data..."

# Run seed data script if it exists
if [ -f "./infrastructure/scripts/seed-data.sh" ]; then
    ./infrastructure/scripts/seed-data.sh
    print_success "Test data seeded"
else
    print_warning "Seed data script not found, skipping"
fi

echo ""
print_success "Setup completed successfully!"
echo ""
echo "Next steps:"
echo "  1. Start the application:"
echo "     ${GREEN}$MAVEN_CMD spring-boot:run${NC}"
echo ""
echo "  2. Access the services:"
echo "     Application:  http://localhost:8080"
echo "     Swagger UI:   http://localhost:8080/swagger-ui.html"
echo "     Prometheus:   http://localhost:9090"
echo "     Grafana:      http://localhost:3000 (admin/admin)"
echo "     Jaeger:       http://localhost:16686"
echo ""
echo "  3. Run demonstrations:"
echo "     See docs/demonstrations/ for curl examples"
echo ""
echo "  4. Run benchmarks:"
echo "     ${GREEN}./infrastructure/scripts/run-benchmarks.sh${NC}"
echo ""
