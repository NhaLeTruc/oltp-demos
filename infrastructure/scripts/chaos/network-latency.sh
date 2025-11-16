#!/bin/bash

###############################################################################
# network-latency.sh - Injects network latency for resilience testing
###############################################################################
#
# Purpose: Simulates network latency to test retry logic, circuit breakers,
#          and timeout handling.
#
# User Story: US5 - Failure Scenarios and Recovery (T190)
#
# Demonstrations:
# 1. Connection retry with exponential backoff
# 2. Circuit breaker triggering on slow calls
# 3. Timeout handling
# 4. Connection pool wait time metrics
#
# Methods:
#   - Docker network: Uses tc (traffic control) inside container
#   - Host network: Uses tc on host interface
#   - Toxiproxy: Uses toxiproxy for advanced network chaos
#
# Usage:
#   ./network-latency.sh --latency 200ms         # Add 200ms latency
#   ./network-latency.sh --latency 500ms --jitter 100ms  # 500ms ± 100ms
#   ./network-latency.sh --packet-loss 10        # 10% packet loss
#   ./network-latency.sh --restore               # Remove all latency
#
# Prerequisites:
#   - tc (traffic control) command (iproute2 package)
#   - Root/sudo access for tc commands
#   - Docker container with network access
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
LATENCY="${LATENCY:-100ms}"
JITTER="${JITTER:-0ms}"
PACKET_LOSS="${PACKET_LOSS:-0}"
RESTORE=false
METHOD="docker"  # docker, host, toxiproxy

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --latency)
            LATENCY="$2"
            shift 2
            ;;
        --jitter)
            JITTER="$2"
            shift 2
            ;;
        --packet-loss)
            PACKET_LOSS="$2"
            shift 2
            ;;
        --restore)
            RESTORE=true
            shift
            ;;
        --container)
            CONTAINER_NAME="$2"
            shift 2
            ;;
        --method)
            METHOD="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --latency DELAY     Add latency (e.g., 100ms, 1s)"
            echo "  --jitter VARIANCE   Add jitter/variance (e.g., 50ms)"
            echo "  --packet-loss PCT   Add packet loss percentage (0-100)"
            echo "  --restore           Remove all latency and restore normal network"
            echo "  --container NAME    Specify container name"
            echo "  --method METHOD     Method: docker, host, toxiproxy (default: docker)"
            echo "  --help              Show this help message"
            echo ""
            echo "Examples:"
            echo "  $0 --latency 200ms                    # Add 200ms delay"
            echo "  $0 --latency 500ms --jitter 100ms    # 500ms ± 100ms"
            echo "  $0 --packet-loss 10                  # 10% packet loss"
            echo "  $0 --restore                         # Remove latency"
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

    if [ "$METHOD" = "docker" ]; then
        if ! docker ps -q -f name="$CONTAINER_NAME" &> /dev/null; then
            echo -e "${RED}Error: Container '$CONTAINER_NAME' not found or not running${NC}"
            exit 1
        fi

        # Check if tc is available in container
        if ! docker exec "$CONTAINER_NAME" which tc &> /dev/null; then
            echo -e "${YELLOW}Warning: tc not found in container${NC}"
            echo "Installing iproute2 in container..."
            docker exec "$CONTAINER_NAME" apt-get update -qq &> /dev/null || true
            docker exec "$CONTAINER_NAME" apt-get install -y iproute2 &> /dev/null || true
        fi
    fi
}

inject_latency_docker() {
    local interface="eth0"

    echo -e "${YELLOW}Injecting network latency into container '$CONTAINER_NAME'...${NC}"
    echo "  Interface: $interface"
    echo "  Latency: $LATENCY"
    echo "  Jitter: $JITTER"
    echo "  Packet Loss: ${PACKET_LOSS}%"
    echo ""

    # Remove existing qdisc (if any)
    docker exec "$CONTAINER_NAME" tc qdisc del dev "$interface" root 2>/dev/null || true

    # Add network emulation
    if [ "$JITTER" != "0ms" ] || [ "$PACKET_LOSS" != "0" ]; then
        docker exec "$CONTAINER_NAME" tc qdisc add dev "$interface" root netem \
            delay "$LATENCY" "$JITTER" \
            loss "${PACKET_LOSS}%"
    else
        docker exec "$CONTAINER_NAME" tc qdisc add dev "$interface" root netem \
            delay "$LATENCY"
    fi

    echo -e "${GREEN}✓ Network latency injected${NC}"
}

restore_network_docker() {
    local interface="eth0"

    echo -e "${YELLOW}Restoring normal network to container '$CONTAINER_NAME'...${NC}"

    # Remove qdisc
    docker exec "$CONTAINER_NAME" tc qdisc del dev "$interface" root 2>/dev/null || true

    echo -e "${GREEN}✓ Network restored to normal${NC}"
}

show_current_latency() {
    local interface="eth0"

    echo -e "${YELLOW}Current network configuration:${NC}"

    if docker exec "$CONTAINER_NAME" tc qdisc show dev "$interface" | grep -q netem; then
        docker exec "$CONTAINER_NAME" tc qdisc show dev "$interface"
    else
        echo "  No latency configured (normal network)"
    fi
    echo ""
}

test_latency() {
    echo -e "${YELLOW}Testing latency with ping...${NC}"

    # Get container IP
    CONTAINER_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$CONTAINER_NAME")

    if [ -n "$CONTAINER_IP" ]; then
        echo "  Pinging $CONTAINER_IP..."
        ping -c 3 "$CONTAINER_IP" || echo "  (Ping may fail due to packet loss)"
    else
        echo "  Could not get container IP"
    fi
    echo ""
}

###############################################################################
# Main Operations
###############################################################################

echo "========================================================================="
echo "  Network Latency Injection - Chaos Engineering"
echo "========================================================================="
echo ""

# Check prerequisites
check_prerequisites

# Show current state
show_current_latency

if [ "$RESTORE" = true ]; then
    # Restore network
    restore_network_docker
else
    # Inject latency
    inject_latency_docker

    # Test latency
    test_latency
fi

echo "========================================================================="
echo "  Next Steps"
echo "========================================================================="
echo ""

if [ "$RESTORE" = true ]; then
    echo "Network restored to normal. You can now:"
    echo "  1. Verify normal operation:"
    echo "     curl http://localhost:8080/api/health"
else
    echo "Network latency injected. Test resilience patterns:"
    echo ""
    echo "  1. Test retry with exponential backoff:"
    echo "     curl -X POST http://localhost:8080/api/demos/failure/retry \\"
    echo "       -H 'Content-Type: application/json' \\"
    echo "       -d '{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":100,\"simulateFailure\":false}'"
    echo ""
    echo "  2. Test circuit breaker (will open after slow calls):"
    echo "     for i in {1..10}; do"
    echo "       curl -X POST http://localhost:8080/api/demos/failure/circuit-breaker \\"
    echo "         -H 'Content-Type: application/json' \\"
    echo "         -d '{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":100,\"simulateFailure\":false}'"
    echo "     done"
    echo ""
    echo "  3. Monitor connection pool metrics:"
    echo "     curl http://localhost:8080/api/demos/failure/retry/metrics"
    echo ""
    echo "  4. Restore network when done:"
    echo "     $0 --restore"
fi
echo ""
