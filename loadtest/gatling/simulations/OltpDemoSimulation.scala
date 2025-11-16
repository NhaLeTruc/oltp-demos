package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * Gatling load test simulation for OLTP Demo.
 *
 * Tests performance optimization demonstrations under load:
 * - Connection pooling
 * - Batch operations
 * - Caching
 * - Database indexing
 *
 * Usage:
 * {{{
 * # Run with Gatling
 * mvn gatling:test -Dgatling.simulationClass=simulations.OltpDemoSimulation
 *
 * # Or with sbt
 * sbt "gatling:test"
 *
 * # Custom parameters
 * mvn gatling:test \
 *   -Dgatling.simulationClass=simulations.OltpDemoSimulation \
 *   -DbaseUrl=http://localhost:8080 \
 *   -Dusers=100 \
 *   -Dduration=60
 * }}}
 *
 * See: specs/001-oltp-core-demo/spec.md - US3: Performance Under Load
 */
class OltpDemoSimulation extends Simulation {

  // Configuration parameters
  val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")
  val users = Integer.getInteger("users", 50)
  val rampDuration = Integer.getInteger("rampDuration", 10).seconds
  val testDuration = Integer.getInteger("duration", 60).seconds

  // HTTP protocol configuration
  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("Gatling/OLTP-Demo-LoadTest")

  // =========================================================================
  // Connection Pooling Scenarios
  // =========================================================================

  val connectionPoolingScenario = scenario("Connection Pooling")
    .exec(
      http("Pooled Connections - 50 iterations")
        .get("/api/demos/performance/connection-pooling?iterations=50")
        .check(status.is(200))
        .check(jsonPath("$.speedupFactor").gt(2.0)) // Expect > 2x speedup
    )
    .pause(1.second, 3.seconds)
    .exec(
      http("Pooled Connections - 100 iterations")
        .get("/api/demos/performance/connection-pooling?iterations=100")
        .check(status.is(200))
        .check(jsonPath("$.pooledResult.successfulQueries").is("100"))
    )
    .pause(2.seconds)
    .exec(
      http("Concurrent Pooling - 20 clients")
        .get("/api/demos/performance/connection-pooling/concurrent?concurrentClients=20&queriesPerClient=10")
        .check(status.is(200))
        .check(jsonPath("$.successfulQueries").gte(190)) // 95% success rate
    )
    .pause(1.second)
    .exec(
      http("Pool Statistics")
        .get("/api/demos/performance/connection-pooling/stats")
        .check(status.is(200))
        .check(jsonPath("$.totalConnections").exists)
    )

  // =========================================================================
  // Batch Operations Scenarios
  // =========================================================================

  val batchOperationsScenario = scenario("Batch Operations")
    .exec(
      http("Batch Operations - 100 records")
        .post("/api/demos/performance/batch-operations")
        .body(StringBody("""{"recordCount":100,"batchSize":50}"""))
        .check(status.is(200))
        .check(jsonPath("$.speedupFactor").gt(3.0)) // Expect > 3x speedup
    )
    .pause(2.seconds, 4.seconds)
    .exec(
      http("Batch Operations - 200 records")
        .post("/api/demos/performance/batch-operations")
        .body(StringBody("""{"recordCount":200,"batchSize":100}"""))
        .check(status.is(200))
        .check(jsonPath("$.batchResult.successfulInserts").is("200"))
    )
    .pause(3.seconds)
    .exec(
      http("JPA Batch Operations")
        .post("/api/demos/performance/batch-operations/jpa?recordCount=100")
        .check(status.is(200))
        .check(jsonPath("$.successfulInserts").is("100"))
    )

  // =========================================================================
  // Caching Scenarios
  // =========================================================================

  val cachingScenario = scenario("Caching")
    .exec(
      http("Clear Cache")
        .post("/api/demos/performance/caching/clear")
        .check(status.is(200))
    )
    .pause(1.second)
    .exec(
      http("Caching - First Access (Cache Miss)")
        .get("/api/demos/performance/caching?accountId=${accountId}&iterations=10")
        .check(status.is(200))
        .check(jsonPath("$.cachedResult.hits").exists)
    )
    .pause(500.milliseconds)
    .exec(
      http("Caching - Second Access (Cache Hit)")
        .get("/api/demos/performance/caching?accountId=${accountId}&iterations=10")
        .check(status.is(200))
        .check(jsonPath("$.speedupFactor").gt(1.5)) // Expect > 1.5x speedup
        .check(jsonPath("$.cacheHitRate").gt(70.0)) // Expect > 70% hit rate
    )
    .pause(1.second)
    .exec(
      http("Cache Statistics")
        .get("/api/demos/performance/caching/stats")
        .check(status.is(200))
        .check(jsonPath("$.enabled").is("true"))
    )

  // =========================================================================
  // Indexing Scenarios
  // =========================================================================

  val indexingScenario = scenario("Database Indexing")
    .exec(
      http("Indexing Comparison")
        .get("/api/demos/performance/indexing?userId=${userId}")
        .check(status.is(200))
        .check(jsonPath("$.speedupFactor").gt(5.0)) // Expect > 5x speedup
    )
    .pause(1.second, 2.seconds)
    .exec(
      http("Indexed Query with EXPLAIN")
        .get("/api/demos/performance/indexing/indexed-query?userId=${userId}")
        .check(status.is(200))
        .check(jsonPath("$.usedIndex").is("true"))
        .check(jsonPath("$.rowsReturned").exists)
    )
    .pause(1.second)
    .exec(
      http("Sequential Scan")
        .get("/api/demos/performance/indexing/sequential-scan?minBalance=0")
        .check(status.is(200))
        .check(jsonPath("$.rowsReturned").exists)
    )

  // =========================================================================
  // jOOQ Advanced Queries Scenarios
  // =========================================================================

  val jooqScenario = scenario("jOOQ Type-Safe Queries")
    .exec(
      http("jOOQ - Accounts with Users")
        .get("/api/demos/performance/jooq/accounts-with-users")
        .check(status.is(200))
        .check(jsonPath("$[*].accountId").exists)
    )
    .pause(1.second)
    .exec(
      http("jOOQ - Transaction Stats")
        .get("/api/demos/performance/jooq/transaction-stats?minTransactionCount=0")
        .check(status.is(200))
    )
    .pause(1.second)
    .exec(
      http("jOOQ - Top Accounts by Type (Window Functions)")
        .get("/api/demos/performance/jooq/top-accounts-by-type")
        .check(status.is(200))
        .check(jsonPath("$[*].rank").exists)
    )
    .pause(1.second)
    .exec(
      http("jOOQ - High Value Accounts (CTE)")
        .get("/api/demos/performance/jooq/high-value-accounts?minBalance=1000")
        .check(status.is(200))
    )
    .pause(1.second)
    .exec(
      http("jOOQ - JOIN Performance Comparison")
        .get("/api/demos/performance/jooq/join-performance")
        .check(status.is(200))
        .check(jsonPath("$.speedupFactor").gt(1.0))
        .check(jsonPath("$.efficientQueryCount").is("1"))
    )

  // =========================================================================
  // Combined Performance Test Scenario
  // =========================================================================

  val combinedPerformanceScenario = scenario("Combined Performance Tests")
    .feed(Iterator.continually(Map(
      "accountId" -> (scala.util.Random.nextInt(100) + 1),
      "userId" -> (scala.util.Random.nextInt(100) + 1)
    )))
    .randomSwitch(
      30.0 -> exec(connectionPoolingScenario),
      25.0 -> exec(cachingScenario),
      20.0 -> exec(batchOperationsScenario),
      15.0 -> exec(indexingScenario),
      10.0 -> exec(jooqScenario)
    )

  // =========================================================================
  // Load Test Setup
  // =========================================================================

  setUp(
    // Connection pooling test - highest load
    connectionPoolingScenario.inject(
      rampUsers(users / 4) during (rampDuration)
    ).protocols(httpProtocol),

    // Caching test - high load
    cachingScenario.inject(
      rampUsers(users / 4) during (rampDuration)
    ).protocols(httpProtocol),

    // Batch operations test - medium load
    batchOperationsScenario.inject(
      rampUsers(users / 5) during (rampDuration)
    ).protocols(httpProtocol),

    // Indexing test - medium load
    indexingScenario.inject(
      rampUsers(users / 5) during (rampDuration)
    ).protocols(httpProtocol),

    // jOOQ test - lower load
    jooqScenario.inject(
      rampUsers(users / 10) during (rampDuration)
    ).protocols(httpProtocol)
  )
    .maxDuration(testDuration)
    .assertions(
      // Global assertions
      global.responseTime.max.lt(5000), // Max response time < 5s
      global.successfulRequests.percent.gt(95), // > 95% success rate

      // Per-scenario assertions
      forAll.responseTime.percentile3.lt(1000), // 95th percentile < 1s
      forAll.failedRequests.percent.lt(5) // < 5% failure rate
    )
}

/**
 * Focused simulation for connection pooling only.
 *
 * Run with:
 * {{{
 * mvn gatling:test -Dgatling.simulationClass=simulations.ConnectionPoolingSimulation
 * }}}
 */
class ConnectionPoolingSimulation extends Simulation {
  val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")
  val users = Integer.getInteger("users", 100)
  val rampDuration = Integer.getInteger("rampDuration", 10).seconds
  val testDuration = Integer.getInteger("duration", 60).seconds

  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")

  val scenario = scenario("Connection Pooling Load Test")
    .exec(
      http("Pooled Connections")
        .get("/api/demos/performance/connection-pooling?iterations=100")
        .check(status.is(200))
    )
    .pause(1.second)
    .exec(
      http("Concurrent Pooling")
        .get("/api/demos/performance/connection-pooling/concurrent?concurrentClients=50&queriesPerClient=10")
        .check(status.is(200))
    )

  setUp(
    scenario.inject(
      rampUsers(users) during (rampDuration)
    ).protocols(httpProtocol)
  )
    .maxDuration(testDuration)
    .assertions(
      global.responseTime.mean.lt(500),
      global.successfulRequests.percent.gt(95)
    )
}
