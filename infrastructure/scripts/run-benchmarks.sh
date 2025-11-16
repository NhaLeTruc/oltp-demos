#!/bin/bash

# OLTP Demo - Execute All Benchmarks
# Usage: ./infrastructure/scripts/run-benchmarks.sh [jmh|gatling|all]

set -e

# Configuration
BENCHMARK_TYPE=${1:-all}
RESULTS_DIR="./benchmark-results/$(date +%Y%m%d-%H%M%S)"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo "⚡ OLTP Demo - Benchmark Suite"
echo "==============================="
echo ""
echo "Benchmark type: $BENCHMARK_TYPE"
echo "Results directory: $RESULTS_DIR"
echo ""

# Create results directory
mkdir -p "$RESULTS_DIR"

# Check if application is running
check_app_running() {
    echo -e "${BLUE}▶ Checking if application is running...${NC}"
    if curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Application is running${NC}"
        return 0
    else
        echo -e "${YELLOW}⚠ Application is not running${NC}"
        read -p "Do you want to start the application? (Y/n) " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Nn]$ ]]; then
            echo -e "${BLUE}▶ Starting application...${NC}"
            ./mvnw spring-boot:run > "$RESULTS_DIR/app.log" 2>&1 &
            APP_PID=$!
            echo $APP_PID > "$RESULTS_DIR/app.pid"

            # Wait for application to start
            MAX_RETRIES=60
            RETRY_COUNT=0
            while ! curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; do
                RETRY_COUNT=$((RETRY_COUNT+1))
                if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
                    echo -e "${RED}✗ Application failed to start${NC}"
                    kill $APP_PID 2>/dev/null || true
                    exit 1
                fi
                echo -n "."
                sleep 1
            done
            echo ""
            echo -e "${GREEN}✓ Application started (PID: $APP_PID)${NC}"
            return 1
        else
            echo -e "${RED}✗ Application must be running to execute benchmarks${NC}"
            exit 1
        fi
    fi
}

# Run JMH benchmarks
run_jmh_benchmarks() {
    echo ""
    echo -e "${BLUE}▶ Running JMH microbenchmarks...${NC}"
    echo ""

    ./mvnw clean test -Dtest=*Benchmark \
        -DargLine="-Xms2g -Xmx2g" \
        > "$RESULTS_DIR/jmh-output.log" 2>&1

    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ JMH benchmarks completed${NC}"

        # Copy JMH results
        if [ -f "jmh-result.json" ]; then
            cp jmh-result.json "$RESULTS_DIR/"
            echo "  Results: $RESULTS_DIR/jmh-result.json"
        fi
        if [ -f "jmh-result.txt" ]; then
            cp jmh-result.txt "$RESULTS_DIR/"
            echo "  Summary: $RESULTS_DIR/jmh-result.txt"
        fi
    else
        echo -e "${RED}✗ JMH benchmarks failed${NC}"
        echo "  See logs: $RESULTS_DIR/jmh-output.log"
        return 1
    fi
}

# Run Gatling load tests
run_gatling_tests() {
    echo ""
    echo -e "${BLUE}▶ Running Gatling load tests...${NC}"
    echo ""

    # Check if Gatling is set up
    if [ ! -d "loadtest/gatling" ]; then
        echo -e "${YELLOW}⚠ Gatling tests not found, skipping${NC}"
        return 0
    fi

    cd loadtest/gatling

    # Run Gatling scenarios
    for scenario in simulations/*.scala; do
        SCENARIO_NAME=$(basename "$scenario" .scala)
        echo -e "${BLUE}  Running scenario: $SCENARIO_NAME${NC}"

        ./mvnw gatling:test -Dgatling.simulationClass="$SCENARIO_NAME" \
            > "../../$RESULTS_DIR/gatling-$SCENARIO_NAME.log" 2>&1

        if [ $? -eq 0 ]; then
            echo -e "${GREEN}  ✓ $SCENARIO_NAME completed${NC}"
        else
            echo -e "${RED}  ✗ $SCENARIO_NAME failed${NC}"
        fi
    done

    # Copy Gatling reports
    if [ -d "target/gatling" ]; then
        cp -r target/gatling/* "../../$RESULTS_DIR/gatling-reports/"
        echo -e "${GREEN}✓ Gatling reports copied to $RESULTS_DIR/gatling-reports/${NC}"
    fi

    cd ../..
}

# Run custom load tests
run_custom_load_tests() {
    echo ""
    echo -e "${BLUE}▶ Running custom load tests...${NC}"
    echo ""

    # High concurrency test
    echo -e "${BLUE}  Test 1: High Concurrency (1000 users, 5 minutes)${NC}"
    ab -n 300000 -c 1000 -t 300 \
        -p <(echo '{"fromAccountId":1,"toAccountId":2,"amount":100.00}') \
        -T 'application/json' \
        http://localhost:8080/api/demos/acid/atomicity/transfer \
        > "$RESULTS_DIR/high-concurrency.txt" 2>&1

    if [ $? -eq 0 ]; then
        echo -e "${GREEN}  ✓ High concurrency test completed${NC}"
        grep "Requests per second" "$RESULTS_DIR/high-concurrency.txt"
    fi

    # Sustained load test
    echo -e "${BLUE}  Test 2: Sustained Load (500 users, 15 minutes)${NC}"
    ab -n 450000 -c 500 -t 900 \
        -p <(echo '{"fromAccountId":1,"toAccountId":2,"amount":100.00}') \
        -T 'application/json' \
        http://localhost:8080/api/demos/acid/atomicity/transfer \
        > "$RESULTS_DIR/sustained-load.txt" 2>&1

    if [ $? -eq 0 ]; then
        echo -e "${GREEN}  ✓ Sustained load test completed${NC}"
        grep "Requests per second" "$RESULTS_DIR/sustained-load.txt"
    fi

    # Spike test
    echo -e "${BLUE}  Test 3: Spike Test (2000 users, 1 minute)${NC}"
    ab -n 120000 -c 2000 -t 60 \
        -p <(echo '{"fromAccountId":1,"toAccountId":2,"amount":100.00}') \
        -T 'application/json' \
        http://localhost:8080/api/demos/acid/atomicity/transfer \
        > "$RESULTS_DIR/spike-test.txt" 2>&1

    if [ $? -eq 0 ]; then
        echo -e "${GREEN}  ✓ Spike test completed${NC}"
        grep "Requests per second" "$RESULTS_DIR/spike-test.txt"
    fi
}

# Generate summary report
generate_summary() {
    echo ""
    echo -e "${BLUE}▶ Generating summary report...${NC}"

    cat > "$RESULTS_DIR/README.md" <<EOF
# Benchmark Results - $(date +"%Y-%m-%d %H:%M:%S")

## Environment

- Application: OLTP Core Capabilities Demo
- Date: $(date)
- Host: $(hostname)
- Java: $(java -version 2>&1 | head -n 1)

## Benchmark Summary

### JMH Microbenchmarks

$(if [ -f "$RESULTS_DIR/jmh-result.txt" ]; then
    cat "$RESULTS_DIR/jmh-result.txt" | head -20
else
    echo "No JMH results available"
fi)

### Load Tests

$(if [ -f "$RESULTS_DIR/high-concurrency.txt" ]; then
    echo "#### High Concurrency Test"
    grep -A 10 "Percentage of the requests" "$RESULTS_DIR/high-concurrency.txt"
fi)

$(if [ -f "$RESULTS_DIR/sustained-load.txt" ]; then
    echo "#### Sustained Load Test"
    grep -A 10 "Percentage of the requests" "$RESULTS_DIR/sustained-load.txt"
fi)

$(if [ -f "$RESULTS_DIR/spike-test.txt" ]; then
    echo "#### Spike Test"
    grep -A 10 "Percentage of the requests" "$RESULTS_DIR/spike-test.txt"
fi)

## Files

$(ls -lh "$RESULTS_DIR")

EOF

    echo -e "${GREEN}✓ Summary report generated: $RESULTS_DIR/README.md${NC}"
}

# Main execution
STARTED_APP=0
check_app_running
if [ $? -eq 1 ]; then
    STARTED_APP=1
fi

case $BENCHMARK_TYPE in
    jmh)
        run_jmh_benchmarks
        ;;
    gatling)
        run_gatling_tests
        ;;
    load)
        run_custom_load_tests
        ;;
    all)
        run_jmh_benchmarks
        run_gatling_tests
        run_custom_load_tests
        ;;
    *)
        echo -e "${RED}✗ Invalid benchmark type: $BENCHMARK_TYPE${NC}"
        echo "Usage: $0 [jmh|gatling|load|all]"
        exit 1
        ;;
esac

generate_summary

# Stop application if we started it
if [ $STARTED_APP -eq 1 ] && [ -f "$RESULTS_DIR/app.pid" ]; then
    APP_PID=$(cat "$RESULTS_DIR/app.pid")
    echo ""
    echo -e "${BLUE}▶ Stopping application (PID: $APP_PID)...${NC}"
    kill $APP_PID 2>/dev/null || true
    rm "$RESULTS_DIR/app.pid"
    echo -e "${GREEN}✓ Application stopped${NC}"
fi

echo ""
echo -e "${GREEN}✓ All benchmarks completed!${NC}"
echo ""
echo "Results available at: $RESULTS_DIR"
echo ""
