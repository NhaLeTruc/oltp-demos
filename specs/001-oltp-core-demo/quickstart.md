# Quickstart Guide: OLTP Core Capabilities Tech Demo

**Estimated Setup Time**: < 15 minutes
**Prerequisites**: Docker, Docker Compose, Java 21+, Maven (or use included Maven wrapper)

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Quick Start (One Command)](#quick-start-one-command)
3. [Manual Setup](#manual-setup)
4. [Running Demonstrations](#running-demonstrations)
5. [Accessing Observability Tools](#accessing-observability-tools)
6. [Load Testing](#load-testing)
7. [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Required Software

| Tool | Version | Purpose | Installation |
|------|---------|---------|--------------|
| **Docker** | 20.10+ | Run PostgreSQL, Redis, Prometheus, Grafana | [Get Docker](https://docs.docker.com/get-docker/) |
| **Docker Compose** | 2.0+ | Orchestrate multi-container stack | Included with Docker Desktop |
| **Java** | 21 LTS | Run Spring Boot application | [AdoptOpenJDK](https://adoptopenjdk.net/) or `brew install openjdk@21` |
| **Maven** | 3.8+ (optional) | Build project | Use included `./mvnw` wrapper |

### Optional (for load testing)

| Tool | Version | Purpose |
|------|---------|---------|
| **Python** | 3.11+ | Run Locust load tests |
| **Scala** | 2.13+ | Run Gatling load tests (or use Maven plugin) |

### System Requirements

- **RAM**: 4GB minimum (8GB recommended)
- **Disk**: 5GB free space
- **CPU**: 2 cores minimum (4+ recommended for realistic load testing)
- **OS**: Linux, macOS, or Windows with WSL2

---

## Quick Start (One Command)

**From project root directory**:

```bash
# 1. Start all infrastructure (PostgreSQL, Redis, Prometheus, Grafana, Jaeger)
cd infrastructure/docker
docker-compose up -d

# 2. Wait for services to be healthy (30-60 seconds)
docker-compose ps  # Check all services show "Up (healthy)"

# 3. Build and run the application
cd ../..
./mvnw clean spring-boot:run

# 4. Seed realistic test data (1M rows)
./infrastructure/scripts/seed-data.sh

# 5. Open in browser
open http://localhost:8080/swagger-ui.html  # API Documentation
open http://localhost:3000                  # Grafana Dashboards
```

**That's it!** The demo is now running.

---

## Manual Setup

### Step 1: Clone Repository

```bash
git clone https://github.com/example/oltp-demos.git
cd oltp-demos
```

### Step 2: Start Infrastructure

```bash
cd infrastructure/docker
docker-compose up -d
```

**Services Started**:
- **PostgreSQL** (port 5432): Primary database
- **Redis** (port 6379): Caching layer
- **Prometheus** (port 9090): Metrics storage
- **Grafana** (port 3000): Metrics visualization
- **Jaeger** (port 16686): Distributed tracing UI

**Verify Services**:
```bash
docker-compose ps

# Expected output:
# NAME                STATUS              PORTS
# postgres-oltp       Up (healthy)        0.0.0.0:5432->5432/tcp
# redis-oltp          Up (healthy)        0.0.0.0:6379->6379/tcp
# prometheus          Up                  0.0.0.0:9090->9090/tcp
# grafana             Up                  0.0.0.0:3000->3000/tcp
# jaeger              Up                  0.0.0.0:16686->16686/tcp
```

### Step 3: Build Application

```bash
cd ../..  # Back to project root

# Using Maven wrapper (no Maven installation required)
./mvnw clean package

# Or with system Maven
mvn clean package
```

**Build Output**:
- Runs all tests (unit, integration, performance)
- Generates code coverage report (target/site/jacoco/index.html)
- Creates executable JAR (target/oltp-demo-1.0.0.jar)

### Step 4: Run Database Migrations

Migrations run automatically on application startup (Flyway), but you can run them manually:

```bash
./mvnw flyway:migrate
```

**Migrations Applied**:
1. V1: Create schema (users, accounts, transactions, etc.)
2. V2: Create indexes
3. V3: Seed reference data (account_types)

### Step 5: Seed Test Data

Generate realistic test data (1M rows for performance testing):

```bash
./infrastructure/scripts/seed-data.sh
```

**Data Generated**:
- 10,000 users
- 25,000 accounts
- 1,000,000 transactions
- 750,000 transfer logs
- 5,000 active sessions

**Time**: ~2-3 minutes

### Step 6: Start Application

```bash
# Option 1: Run with Maven (development mode with hot reload)
./mvnw spring-boot:run

# Option 2: Run packaged JAR (production-like)
java -jar target/oltp-demo-1.0.0.jar

# Option 3: Run with specific profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

**Application Startup**:
```
  ____  _   _____ ____    ____
 / __ \| | |_   _|  _ \  |  _ \  ___ _ __ ___   ___
/ / _` | |   | | | |_) | | | | |/ _ \ '_ ` _ \ / _ \
\ (_| | |___| |_|  __/  | |_| |  __/ | | | | | (_) |
 \____/|_____|___|_|     |____/ \___|_| |_| |_|\___/

Application started on port 8080
```

### Step 7: Verify Installation

```bash
# Health check
curl http://localhost:8080/api/health

# Expected output:
{
  "status": "UP",
  "components": {
    "database": {"status": "UP"},
    "redis": {"status": "UP"},
    "hikaricp": {"status": "UP", "details": {"active": 0, "idle": 10}}
  }
}

# Metrics endpoint
curl http://localhost:8080/api/metrics | grep hikaricp

# API documentation
open http://localhost:8080/swagger-ui.html
```

---

## Running Demonstrations

### ACID Transaction Demonstrations

#### 1. Atomicity (All-or-Nothing)

```bash
# Successful transfer
curl -X POST http://localhost:8080/api/demos/acid/atomicity/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00
  }'

# Expected: Both accounts updated atomically
# Response: {"transactionId": 123, "status": "COMPLETED"}

# Failed transfer (insufficient funds)
curl -X POST http://localhost:8080/api/demos/acid/atomicity/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 999999.00
  }'

# Expected: Transaction rolled back, no partial state
# Response: {"error": "INSUFFICIENT_FUNDS", "status": "ROLLED_BACK"}
```

#### 2. Consistency (Constraint Enforcement)

```bash
# Attempt to create negative balance
curl -X POST http://localhost:8080/api/demos/acid/consistency/enforce-constraints \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 99999999.00
  }'

# Expected: Database rejects transaction (constraint violation)
# Response: {"error": "CONSTRAINT_VIOLATION", "message": "Balance cannot be negative"}
```

#### 3. Isolation (Concurrent Transactions)

```bash
# Run concurrent transfers with SERIALIZABLE isolation
curl -X POST "http://localhost:8080/api/demos/acid/isolation/concurrent-transfers?isolationLevel=SERIALIZABLE" \
  -H "Content-Type: application/json" \
  -d '{
    "transfers": [
      {"fromAccountId": 1, "toAccountId": 2, "amount": 50.00},
      {"fromAccountId": 1, "toAccountId": 3, "amount": 75.00},
      {"fromAccountId": 1, "toAccountId": 4, "amount": 25.00}
    ]
  }'

# Expected: Transactions execute in isolation, conflicts detected
# Response: {"results": [...], "conflictsDetected": 2}
```

#### 4. Durability (Crash Recovery)

```bash
# 1. Execute transactions and note correlation ID
curl -X POST http://localhost:8080/api/demos/acid/atomicity/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00,
    "correlationId": "550e8400-e29b-41d4-a716-446655440000"
  }'

# 2. Simulate crash (kill database container)
docker stop postgres-oltp

# 3. Restart database
docker start postgres-oltp

# 4. Verify committed transaction survived
curl "http://localhost:8080/api/demos/acid/durability/crash-recovery?correlationId=550e8400-e29b-41d4-a716-446655440000"

# Expected: Transaction found in transfer_logs
# Response: {"durabilityVerified": true, "transferLogs": [...]}
```

### Concurrency Demonstrations

#### 1. Optimistic Locking

```bash
# Concurrent operations on same account
curl -X POST http://localhost:8080/api/demos/concurrency/optimistic-locking \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": 1,
    "operations": [
      {"amount": 50.00, "type": "DEPOSIT"},
      {"amount": 25.00, "type": "WITHDRAWAL"},
      {"amount": 75.00, "type": "DEPOSIT"}
    ]
  }'

# Expected: Version conflicts detected, retries performed
# Response: {"conflictsDetected": 2, "retriesPerformed": 2, "finalBalance": 1100.00}
```

#### 2. Pessimistic Locking

```bash
# Transfer with exclusive row lock
curl -X POST http://localhost:8080/api/demos/concurrency/pessimistic-locking \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00
  }'

# Expected: Explicit lock acquired, no conflicts
# Response: {"lockAcquiredAt": "...", "lockReleasedAt": "...", "lockHoldTime": 105}
```

#### 3. Deadlock Detection

```bash
# Create deadlock scenario (A→B and B→A simultaneously)
curl -X POST http://localhost:8080/api/demos/concurrency/deadlock \
  -H "Content-Type: application/json" \
  -d '{
    "accountIdA": 1,
    "accountIdB": 2,
    "amount": 50.00
  }'

# Expected: Deadlock detected by PostgreSQL, one transaction aborted and retried
# Response: {"deadlockDetected": true, "retriedSuccessfully": true, "resolutionTime": 150}
```

### Performance Demonstrations

#### 1. Connection Pooling

```bash
# Compare pooled vs unpooled connection performance
curl "http://localhost:8080/api/demos/performance/connection-pooling?queryCount=100"

# Expected: ~10x performance improvement with pooling
# Response: {
#   "pooledMetrics": {"averageTime": 2.5, "throughput": 400},
#   "unpooledMetrics": {"averageTime": 52.3, "throughput": 19},
#   "performanceGain": 20.9
# }
```

#### 2. Batch Operations

```bash
# Compare individual vs batch inserts
curl -X POST "http://localhost:8080/api/demos/performance/batch-operations?rowCount=1000"

# Expected: ~20-50x throughput improvement
# Response: {
#   "individualInserts": {"totalTime": 8500, "throughput": 117},
#   "batchInserts": {"totalTime": 420, "throughput": 2380},
#   "throughputGain": 20.3
# }
```

#### 3. Caching

```bash
# Measure cache performance
curl "http://localhost:8080/api/demos/performance/caching?accountId=1&requestCount=100"

# Expected: Sub-millisecond latency for cache hits
# Response: {
#   "cacheHits": 99,
#   "cacheMisses": 1,
#   "hitRatio": 99.0,
#   "averageLatencyWithCache": 0.8,
#   "averageLatencyWithoutCache": 5.2,
#   "latencyImprovement": 6.5
# }
```

#### 4. Indexing

```bash
# Compare indexed vs full table scan
curl "http://localhost:8080/api/demos/performance/indexing?useIndex=true"

# Expected: Index scan 100-1000x faster
# Response: {
#   "indexedQuery": {"executionTime": 2.3, "planType": "INDEX_SCAN", "rowsScanned": 1},
#   "fullScanQuery": {"executionTime": 245.7, "planType": "SEQ_SCAN", "rowsScanned": 1000000},
#   "performanceGain": 106.8
# }
```

---

## Accessing Observability Tools

### Prometheus (Metrics Storage)

**URL**: http://localhost:9090

**Sample Queries**:
```promql
# Connection pool metrics
hikaricp_connections_active{pool="HikariPool-1"}

# Transaction throughput (requests/second)
rate(transaction_throughput_total[1m])

# Query latency (p95)
histogram_quantile(0.95, sum(rate(query_duration_seconds_bucket[5m])) by (le))

# Error rate
rate(http_requests_total{status="500"}[1m])
```

### Grafana (Metrics Visualization)

**URL**: http://localhost:3000
**Default Credentials**: admin / admin (change on first login)

**Pre-configured Dashboards**:
1. **OLTP Overview**: Transaction throughput, latency, error rates
2. **Database Metrics**: Connection pool, query performance, cache hit ratios
3. **JVM Metrics**: Heap usage, GC pauses, thread counts

**Import Additional Dashboards**:
- Spring Boot Micrometer: Dashboard ID 4701
- PostgreSQL: Dashboard ID 9628
- HikariCP: Dashboard ID 12835

### Jaeger (Distributed Tracing)

**URL**: http://localhost:16686

**Find Traces**:
1. Service: `oltp-demo`
2. Operation: `POST /api/demos/acid/atomicity/transfer`
3. Tag: `correlation_id=<your-correlation-id>`

**Sample Trace**:
```
Transfer Transaction [105ms total]
  ├─ Acquire Connection [0.8ms]
  ├─ BEGIN Transaction [0.3ms]
  ├─ SELECT Account (from) [1.2ms]
  ├─ SELECT Account (to) [1.1ms]
  ├─ UPDATE Account (from) [2.5ms]
  ├─ UPDATE Account (to) [2.3ms]
  ├─ INSERT TransferLog [1.8ms]
  └─ COMMIT [0.9ms]
```

### Logs (Structured JSON)

**View Logs**:
```bash
# Follow application logs
./mvnw spring-boot:run | jq .

# Filter by correlation ID
cat logs/application.log | jq 'select(.correlation_id == "550e8400-e29b-41d4-a716-446655440000")'

# Filter by log level
cat logs/application.log | jq 'select(.level == "ERROR")'
```

**Sample Log Entry**:
```json
{
  "timestamp": "2025-11-16T10:30:00.123Z",
  "level": "INFO",
  "logger": "com.oltp.demo.service.TransferService",
  "message": "Transfer completed successfully",
  "correlation_id": "550e8400-e29b-41d4-a716-446655440000",
  "transaction_id": 12345,
  "from_account_id": 1,
  "to_account_id": 2,
  "amount": 100.00,
  "duration_ms": 105
}
```

---

## Load Testing

### Gatling (JVM-based)

**Run Load Test**:
```bash
./mvnw gatling:test -Dgatling.simulationClass=com.oltp.demo.loadtest.OltpDemoSimulation

# Or run script
./infrastructure/scripts/run-benchmarks.sh
```

**Scenarios**:
- **Smoke Test**: 10 users, 1 minute (verify basic functionality)
- **Load Test**: 100 users, 5 minutes (normal load)
- **Stress Test**: 1000 users, 10 minutes (find breaking point)

**Results**: `target/gatling/results/`

### Locust (Python-based)

**Install Locust**:
```bash
cd loadtest/locust
pip install -r requirements.txt
```

**Run Load Test**:
```bash
locust -f locustfile.py --host=http://localhost:8080

# Open web UI: http://localhost:8089
# Configure: 100 users, spawn rate 10/sec, duration 5 minutes
```

**Scenarios**:
- ACID demonstrations (transfers, concurrent operations)
- Concurrency demonstrations (optimistic/pessimistic locking)
- Performance demonstrations (connection pooling, caching)

### JMH (Microbenchmarks)

**Run Benchmarks**:
```bash
# Run all benchmarks
./mvnw clean test -Pbenchmarks

# Run specific benchmark
./mvnw clean test -Pbenchmarks -Dbenchmark=QueryPerformanceBenchmark
```

**Results**: `target/jmh-results.json`

---

## Troubleshooting

### Common Issues

#### 1. Port Already in Use

**Error**: `Bind for 0.0.0.0:5432 failed: port is already allocated`

**Solution**:
```bash
# Check what's using the port
lsof -i :5432

# Stop conflicting service or change port in docker-compose.yml
```

#### 2. Database Connection Failed

**Error**: `Unable to acquire JDBC Connection`

**Solution**:
```bash
# Verify PostgreSQL is running
docker-compose ps postgres-oltp

# Check PostgreSQL logs
docker-compose logs postgres-oltp

# Restart PostgreSQL
docker-compose restart postgres-oltp
```

#### 3. Out of Memory

**Error**: `java.lang.OutOfMemoryError: Java heap space`

**Solution**:
```bash
# Increase JVM heap size
export MAVEN_OPTS="-Xmx2g"
./mvnw spring-boot:run

# Or for packaged JAR
java -Xmx2g -jar target/oltp-demo-1.0.0.jar
```

#### 4. Tests Failing

**Error**: `Testcontainers: Could not find a valid Docker environment`

**Solution**:
```bash
# Verify Docker is running
docker ps

# Pull required images
docker pull postgres:15-alpine
docker pull redis:7-alpine

# Check Testcontainers configuration
cat src/test/resources/testcontainers.properties
```

#### 5. Flyway Migration Failed

**Error**: `Migration checksum mismatch`

**Solution**:
```bash
# Clean and re-run migrations (CAUTION: Destroys data)
./mvnw flyway:clean flyway:migrate

# Or manually repair
./mvnw flyway:repair
```

### Performance Issues

#### Slow Queries

**Diagnose**:
```bash
# Check slow query log
docker-compose exec postgres-oltp tail -f /var/log/postgresql/slow-queries.log

# Analyze query plan
docker-compose exec postgres-oltp psql -U oltp_user -d oltp_db -c "EXPLAIN ANALYZE SELECT * FROM accounts WHERE balance > 1000;"
```

**Fix**:
- Add missing indexes
- Adjust connection pool size
- Enable query result caching

#### High Latency

**Diagnose**:
```bash
# Check HikariCP metrics
curl http://localhost:8080/api/metrics | grep hikaricp

# Check database connections
docker-compose exec postgres-oltp psql -U oltp_user -d oltp_db -c "SELECT count(*) FROM pg_stat_activity;"
```

**Fix**:
- Increase HikariCP pool size (`spring.datasource.hikari.maximum-pool-size`)
- Add Redis caching for hot data
- Optimize database queries

### Getting Help

**Check Logs**:
```bash
# Application logs
tail -f logs/application.log

# Docker Compose logs
docker-compose logs -f
```

**Enable Debug Logging**:
```yaml
# application.yml
logging:
  level:
    com.oltp.demo: DEBUG
    org.hibernate.SQL: DEBUG
    com.zaxxer.hikari: DEBUG
```

**Community Support**:
- GitHub Issues: https://github.com/example/oltp-demos/issues
- Discussions: https://github.com/example/oltp-demos/discussions

---

## Next Steps

1. **Explore Demonstrations**: Run each demo scenario and observe metrics in Grafana
2. **Read Documentation**: Check `docs/demonstrations/` for detailed explanations
3. **Run Load Tests**: Use Gatling/Locust to test under realistic load
4. **Modify & Experiment**: Change configuration, add custom scenarios
5. **Review Code**: Study implementation in `src/main/java/com/oltp/demo/`

---

**Quickstart Complete!** You should now have a fully functional OLTP demo system running locally.

For detailed documentation, see:
- [Architecture Overview](../../../docs/architecture/README.md)
- [Demonstration Guides](../../../docs/demonstrations/README.md)
- [API Reference](./contracts/openapi.yaml)
- [Data Model](./data-model.md)
- [Technology Research](./research.md)
