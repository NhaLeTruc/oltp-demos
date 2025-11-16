"""
Performance demonstration load test scenarios.

Tests connection pooling, batch operations, caching, and indexing
under realistic load conditions.

Usage:
    # Run connection pooling test
    locust -f scenarios/performance_demo.py --host=http://localhost:8080 \\
           --users 100 --spawn-rate 10 --run-time 60s --headless

    # Run with web UI for monitoring
    locust -f scenarios/performance_demo.py --host=http://localhost:8080

See: specs/001-oltp-core-demo/spec.md - US3: Performance Under Load
"""

import random
import sys
from pathlib import Path

# Add parent directory to path to import locustfile
sys.path.insert(0, str(Path(__file__).parent.parent))

from locust import HttpUser, TaskSet, between, task
from locustfile import OltpDemoUser, generate_account_data, generate_transfer_data

import logging

logger = logging.getLogger(__name__)


# =============================================================================
# Connection Pooling Test Scenarios
# =============================================================================

class ConnectionPoolingTasks(TaskSet):
    """
    Tasks for testing connection pooling performance.

    Demonstrates the impact of HikariCP connection pooling on:
    - Query response times
    - Concurrent connection handling
    - Connection pool statistics
    """

    @task(10)
    def test_pooled_connections(self):
        """
        Test pooled connection performance.

        This should be significantly faster than unpooled connections.
        """
        iterations = random.choice([10, 50, 100])

        response = self.client.get(
            f"/api/demos/performance/connection-pooling?iterations={iterations}",
            name="Connection Pooling (Pooled)",
            catch_response=True
        )

        if response.status_code == 200:
            try:
                data = response.json()
                speedup = data.get("speedupFactor", 0)

                # Verify pooling provides significant speedup
                if speedup > 2.0:
                    response.success()
                    logger.debug(f"Connection pooling speedup: {speedup:.1f}x")
                else:
                    response.failure(f"Insufficient speedup: {speedup:.1f}x (expected > 2x)")

            except Exception as e:
                response.failure(f"Failed to parse response: {e}")
        else:
            response.failure(f"HTTP {response.status_code}")

    @task(5)
    def test_concurrent_pooling(self):
        """
        Test pool performance under concurrent load.

        Simulates multiple clients hitting the pool simultaneously.
        """
        concurrent_clients = random.choice([10, 20, 50])
        queries_per_client = random.choice([5, 10])

        response = self.client.get(
            f"/api/demos/performance/connection-pooling/concurrent"
            f"?concurrentClients={concurrent_clients}&queriesPerClient={queries_per_client}",
            name="Concurrent Connection Pooling",
            catch_response=True
        )

        if response.status_code == 200:
            try:
                data = response.json()
                success_rate = (data.get("successfulQueries", 0) * 100.0) / data.get("totalQueries", 1)

                # Verify high success rate under concurrency
                if success_rate >= 95.0:
                    response.success()
                else:
                    response.failure(f"Low success rate: {success_rate:.1f}%")

            except Exception as e:
                response.failure(f"Failed to parse response: {e}")
        else:
            response.failure(f"HTTP {response.status_code}")

    @task(2)
    def get_pool_statistics(self):
        """
        Retrieve connection pool statistics.

        Monitor pool health during load test.
        """
        response = self.client.get(
            "/api/demos/performance/connection-pooling/stats",
            name="Pool Statistics"
        )


# =============================================================================
# Batch Operations Test Scenarios
# =============================================================================

class BatchOperationsTasks(TaskSet):
    """
    Tasks for testing batch operation performance.

    Demonstrates throughput improvements from batching:
    - Individual vs batch inserts
    - Different batch sizes
    - JPA vs JDBC batch performance
    """

    @task(8)
    def test_batch_operations(self):
        """
        Test batch insert performance.

        Compare individual vs batch inserts.
        """
        record_count = random.choice([50, 100, 200])
        batch_size = random.choice([50, 100])

        response = self.client.post(
            "/api/demos/performance/batch-operations",
            json={"recordCount": record_count, "batchSize": batch_size},
            name="Batch Operations",
            catch_response=True
        )

        if response.status_code == 200:
            try:
                data = response.json()
                speedup = data.get("speedupFactor", 0)

                # Verify batching provides significant speedup
                if speedup > 3.0:
                    response.success()
                    logger.debug(f"Batch operations speedup: {speedup:.1f}x")
                else:
                    response.failure(f"Insufficient speedup: {speedup:.1f}x (expected > 3x)")

            except Exception as e:
                response.failure(f"Failed to parse response: {e}")
        else:
            response.failure(f"HTTP {response.status_code}")

    @task(4)
    def test_jpa_batch(self):
        """
        Test JPA batch insert performance.

        Compare JPA batch processing to JDBC.
        """
        record_count = random.choice([50, 100])

        response = self.client.post(
            f"/api/demos/performance/batch-operations/jpa?recordCount={record_count}",
            name="JPA Batch Operations",
            catch_response=True
        )

        if response.status_code == 200:
            try:
                data = response.json()
                records_per_sec = data.get("recordsPerSecond", 0)

                # Verify acceptable throughput
                if records_per_sec > 50:
                    response.success()
                else:
                    response.failure(f"Low throughput: {records_per_sec:.1f} records/sec")

            except Exception as e:
                response.failure(f"Failed to parse response: {e}")
        else:
            response.failure(f"HTTP {response.status_code}")


# =============================================================================
# Caching Test Scenarios
# =============================================================================

class CachingTasks(TaskSet):
    """
    Tasks for testing caching performance.

    Demonstrates cache hit benefits:
    - Cache hits vs misses
    - Cache invalidation
    - Cache statistics
    """

    def on_start(self):
        """Initialize account IDs for consistent cache testing."""
        self.account_ids = list(range(1, 101))

    @task(15)
    def test_caching(self):
        """
        Test cache performance with repeated queries.

        First query should miss cache, subsequent queries should hit.
        """
        account_id = random.choice(self.account_ids)
        iterations = random.choice([5, 10])

        response = self.client.get(
            f"/api/demos/performance/caching?accountId={account_id}&iterations={iterations}",
            name="Caching Performance",
            catch_response=True
        )

        if response.status_code == 200:
            try:
                data = response.json()
                speedup = data.get("speedupFactor", 0)
                hit_rate = data.get("cacheHitRate", 0)

                # Verify caching provides speedup and high hit rate
                if speedup > 1.5 and hit_rate > 70:
                    response.success()
                    logger.debug(f"Cache speedup: {speedup:.1f}x, hit rate: {hit_rate:.1f}%")
                else:
                    response.failure(
                        f"Insufficient performance: {speedup:.1f}x speedup, "
                        f"{hit_rate:.1f}% hit rate"
                    )

            except Exception as e:
                response.failure(f"Failed to parse response: {e}")
        else:
            response.failure(f"HTTP {response.status_code}")

    @task(2)
    def clear_cache(self):
        """
        Clear cache to test cold cache performance.

        Forces cache misses on next queries.
        """
        response = self.client.post(
            "/api/demos/performance/caching/clear",
            name="Clear Cache"
        )

    @task(3)
    def get_cache_statistics(self):
        """
        Retrieve cache statistics.

        Monitor cache health during load test.
        """
        response = self.client.get(
            "/api/demos/performance/caching/stats",
            name="Cache Statistics"
        )


# =============================================================================
# Indexing Test Scenarios
# =============================================================================

class IndexingTasks(TaskSet):
    """
    Tasks for testing database indexing performance.

    Demonstrates index benefits:
    - Indexed queries vs sequential scans
    - Query execution plans
    - Query performance metrics
    """

    @task(10)
    def test_indexing(self):
        """
        Test indexed query performance.

        Compare indexed lookup vs sequential scan.
        """
        user_id = random.randint(1, 100)

        response = self.client.get(
            f"/api/demos/performance/indexing?userId={user_id}",
            name="Indexing Performance",
            catch_response=True
        )

        if response.status_code == 200:
            try:
                data = response.json()
                speedup = data.get("speedupFactor", 0)

                # Verify indexing provides massive speedup
                if speedup > 5.0:
                    response.success()
                    logger.debug(f"Index speedup: {speedup:.1f}x")
                else:
                    response.failure(f"Insufficient speedup: {speedup:.1f}x (expected > 5x)")

            except Exception as e:
                response.failure(f"Failed to parse response: {e}")
        else:
            response.failure(f"HTTP {response.status_code}")

    @task(5)
    def test_indexed_query_explain(self):
        """
        Test indexed query with EXPLAIN plan.

        Verify index usage.
        """
        user_id = random.randint(1, 100)

        response = self.client.get(
            f"/api/demos/performance/indexing/indexed-query?userId={user_id}",
            name="Indexed Query (EXPLAIN)",
            catch_response=True
        )

        if response.status_code == 200:
            try:
                data = response.json()
                used_index = data.get("usedIndex", False)

                if used_index:
                    response.success()
                else:
                    response.failure("Query did not use index")

            except Exception as e:
                response.failure(f"Failed to parse response: {e}")
        else:
            response.failure(f"HTTP {response.status_code}")


# =============================================================================
# Combined Performance Test User
# =============================================================================

class PerformanceDemoUser(OltpDemoUser):
    """
    User that runs all performance test scenarios.

    This user type distributes load across all performance optimization
    demonstrations:
    - Connection pooling (high priority)
    - Batch operations (medium priority)
    - Caching (high priority)
    - Indexing (medium priority)
    """

    wait_time = between(1, 3)

    tasks = {
        ConnectionPoolingTasks: 4,  # 40% of tasks
        CachingTasks: 3,             # 30% of tasks
        BatchOperationsTasks: 2,     # 20% of tasks
        IndexingTasks: 1             # 10% of tasks
    }


# =============================================================================
# Specialized User Types for Focused Testing
# =============================================================================

class ConnectionPoolingUser(OltpDemoUser):
    """User focused only on connection pooling tests."""
    wait_time = between(0.5, 2)
    tasks = [ConnectionPoolingTasks]


class BatchOperationsUser(OltpDemoUser):
    """User focused only on batch operation tests."""
    wait_time = between(1, 4)  # Longer wait due to batch operations
    tasks = [BatchOperationsTasks]


class CachingUser(OltpDemoUser):
    """User focused only on caching tests."""
    wait_time = between(0.5, 2)
    tasks = [CachingTasks]


class IndexingUser(OltpDemoUser):
    """User focused only on indexing tests."""
    wait_time = between(1, 3)
    tasks = [IndexingTasks]


if __name__ == "__main__":
    # Run with the combined performance demo user
    import os
    os.system(
        "locust -f scenarios/performance_demo.py "
        "--host=http://localhost:8080 "
        "--users 50 --spawn-rate 5"
    )
