"""
Locust load testing configuration for OLTP Demo.

This base locustfile provides common configuration and utility functions
for all performance test scenarios.

Usage:
    # Run with web UI
    locust -f locustfile.py --host=http://localhost:8080

    # Headless mode with specific users and spawn rate
    locust -f locustfile.py --host=http://localhost:8080 --users 100 --spawn-rate 10 --run-time 60s --headless

    # Run specific scenario
    locust -f scenarios/performance_demo.py --host=http://localhost:8080 --users 50 --spawn-rate 5

See: specs/001-oltp-core-demo/spec.md - US3: Performance Under Load
"""

import logging
import random
import time
from typing import Optional

from locust import HttpUser, between, events, task
from locust.runners import MasterRunner, WorkerRunner

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class OltpDemoUser(HttpUser):
    """
    Base user class for OLTP demonstration load tests.

    Provides common configuration and utility methods for all test scenarios.
    """

    # Wait time between tasks (1-3 seconds)
    wait_time = between(1, 3)

    # Common headers
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json"
    }

    def on_start(self):
        """
        Called when a user starts executing tasks.
        Initialize user state and resources.
        """
        logger.info(f"User {self.environment.runner.user_count} started")
        self.user_id = random.randint(1, 100)
        self.account_id = random.randint(1, 100)

    def on_stop(self):
        """
        Called when a user stops executing tasks.
        Cleanup resources.
        """
        logger.info(f"User stopped")

    def make_request(
        self,
        method: str,
        endpoint: str,
        name: Optional[str] = None,
        **kwargs
    ):
        """
        Make an HTTP request with error handling and metrics.

        Args:
            method: HTTP method (GET, POST, etc.)
            endpoint: API endpoint path
            name: Custom name for the request in statistics
            **kwargs: Additional arguments for the request

        Returns:
            Response object or None if request failed
        """
        kwargs.setdefault("headers", self.headers)
        kwargs.setdefault("catch_response", True)

        with self.client.request(
            method,
            endpoint,
            name=name or f"{method} {endpoint}",
            **kwargs
        ) as response:
            try:
                if response.status_code == 200:
                    response.success()
                    return response
                elif response.status_code == 500:
                    response.failure(f"Server error: {response.text[:100]}")
                elif response.status_code == 404:
                    response.failure(f"Not found: {endpoint}")
                else:
                    response.failure(f"Unexpected status: {response.status_code}")

            except Exception as e:
                response.failure(f"Exception: {str(e)}")
                logger.error(f"Request failed: {e}")

        return None


class DefaultUser(OltpDemoUser):
    """
    Default user with basic health check tasks.

    This user is used when no specific scenario is provided.
    """

    @task(10)
    def health_check(self):
        """Check application health endpoint."""
        self.make_request("GET", "/actuator/health", name="Health Check")

    @task(5)
    def info_endpoint(self):
        """Check application info endpoint."""
        self.make_request("GET", "/actuator/info", name="Info Check")


# =============================================================================
# Locust Event Handlers
# =============================================================================

@events.init.add_listener
def on_locust_init(environment, **kwargs):
    """
    Called when Locust is initialized.

    Configure test environment and logging.
    """
    logger.info("Locust initialized")
    logger.info(f"Host: {environment.host}")

    if isinstance(environment.runner, MasterRunner):
        logger.info("Running in MASTER mode")
    elif isinstance(environment.runner, WorkerRunner):
        logger.info("Running in WORKER mode")
    else:
        logger.info("Running in STANDALONE mode")


@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    """
    Called when test starts.

    Log test configuration and setup.
    """
    logger.info("=" * 80)
    logger.info("OLTP Demo Load Test Starting")
    logger.info("=" * 80)
    logger.info(f"Target host: {environment.host}")


@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    """
    Called when test stops.

    Log final statistics and cleanup.
    """
    logger.info("=" * 80)
    logger.info("OLTP Demo Load Test Completed")
    logger.info("=" * 80)

    # Log summary statistics
    stats = environment.stats
    logger.info(f"Total requests: {stats.total.num_requests}")
    logger.info(f"Total failures: {stats.total.num_failures}")
    logger.info(f"Average response time: {stats.total.avg_response_time:.2f}ms")
    logger.info(f"RPS: {stats.total.total_rps:.2f}")


@events.request.add_listener
def on_request(request_type, name, response_time, response_length, exception, **kwargs):
    """
    Called on every request.

    Can be used for custom metrics or logging.
    """
    if exception:
        logger.error(f"Request failed: {name} - {exception}")


# =============================================================================
# Utility Functions
# =============================================================================

def generate_account_data():
    """
    Generate random account data for test requests.

    Returns:
        dict: Account data with random values
    """
    return {
        "userId": random.randint(1, 100),
        "accountTypeId": random.randint(1, 3),
        "balance": round(random.uniform(100, 10000), 2),
        "status": random.choice(["ACTIVE", "ACTIVE", "ACTIVE", "SUSPENDED"])
    }


def generate_transfer_data():
    """
    Generate random transfer data for test requests.

    Returns:
        dict: Transfer data with random values
    """
    from_account = random.randint(1, 100)
    to_account = random.randint(1, 100)

    # Ensure different accounts
    while to_account == from_account:
        to_account = random.randint(1, 100)

    return {
        "fromAccountId": from_account,
        "toAccountId": to_account,
        "amount": round(random.uniform(10, 500), 2)
    }


def weighted_choice(choices_with_weights):
    """
    Make a weighted random choice.

    Args:
        choices_with_weights: List of tuples (choice, weight)

    Returns:
        Selected choice
    """
    choices, weights = zip(*choices_with_weights)
    return random.choices(choices, weights=weights, k=1)[0]


# =============================================================================
# Performance Metrics Helpers
# =============================================================================

class PerformanceThresholds:
    """
    Define performance thresholds for different operations.

    Used to validate that performance meets requirements.
    """

    # Response time thresholds (milliseconds)
    SIMPLE_QUERY_MAX = 50
    COMPLEX_QUERY_MAX = 200
    TRANSACTION_MAX = 100
    BATCH_OPERATION_MAX = 500

    # Success rate thresholds (percentage)
    MIN_SUCCESS_RATE = 95.0

    # Throughput thresholds (requests per second)
    MIN_RPS = 100


def check_performance_thresholds(stats):
    """
    Check if performance meets defined thresholds.

    Args:
        stats: Locust statistics object

    Returns:
        bool: True if all thresholds met, False otherwise
    """
    issues = []

    # Check success rate
    total_requests = stats.total.num_requests
    total_failures = stats.total.num_failures

    if total_requests > 0:
        success_rate = ((total_requests - total_failures) * 100.0) / total_requests

        if success_rate < PerformanceThresholds.MIN_SUCCESS_RATE:
            issues.append(
                f"Success rate ({success_rate:.1f}%) below threshold "
                f"({PerformanceThresholds.MIN_SUCCESS_RATE}%)"
            )

    # Check RPS
    if stats.total.total_rps < PerformanceThresholds.MIN_RPS:
        issues.append(
            f"RPS ({stats.total.total_rps:.1f}) below threshold "
            f"({PerformanceThresholds.MIN_RPS})"
        )

    if issues:
        logger.warning("Performance threshold violations:")
        for issue in issues:
            logger.warning(f"  - {issue}")
        return False

    logger.info("All performance thresholds met!")
    return True


if __name__ == "__main__":
    # This allows running locust directly with this file
    import os
    os.system("locust -f locustfile.py --host=http://localhost:8080")
