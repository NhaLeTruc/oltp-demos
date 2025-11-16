# Implementation Plan: OLTP Core Capabilities Tech Demo

**Branch**: `001-oltp-core-demo` | **Date**: 2025-11-16 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-oltp-core-demo/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Build a comprehensive tech demo showcasing OLTP core capabilities (ACID transactions, concurrency control, performance optimization, and observability) using production-grade Java-based architecture. The system will demonstrate real-world transaction processing patterns through executable examples, load tests, and instrumentation that developers can run locally and learn from.

**Technical Approach**: Java-based monolithic application using Spring Boot for core transaction processing, PostgreSQL for ACID-compliant storage, with supporting tools in their native languages (Python for load generation, shell scripts for automation). All components containerized for easy local deployment with Docker Compose.

## Technical Context

**Language/Version**: Java 21 LTS (core application), Python 3.11 (load testing), Bash (automation scripts)

**Primary Dependencies**:
- **Spring Boot 3.2+**: Core application framework
- **HikariCP**: High-performance connection pooling
- **Spring Data JPA**: ORM with Hibernate for demonstration
- **jOOQ**: Type-safe SQL query builder for advanced examples
- **Micrometer**: Metrics instrumentation (Prometheus integration)
- **Logback**: Structured JSON logging
- **OpenTelemetry Java Agent**: Distributed tracing

**Storage**:
- **Primary**: PostgreSQL 15+ (ACID-compliant relational database)
- **Caching**: Redis 7+ (for caching demonstrations)
- **Metrics Storage**: Prometheus (time-series metrics)

**Testing**:
- **JUnit 5**: Unit and integration testing framework
- **Testcontainers**: Docker-based integration tests with real PostgreSQL
- **JMH (Java Microbenchmark Harness)**: Performance benchmarking
- **Gatling**: Load testing (JVM-based, Scala DSL)
- **Locust**: Alternative load testing (Python-based, for comparison)
- **ArchUnit**: Architecture and layering tests

**Target Platform**:
- **Development**: Docker Compose on Linux/macOS/Windows
- **Runtime**: JVM 21+ on Linux containers
- **Database**: PostgreSQL 15+ container

**Project Type**: Single monolithic application with multiple demonstration modules

**Performance Goals**:
- Point queries: < 5ms (p95)
- Simple transactions: < 10ms (p95)
- Complex transactions: < 50ms (p95)
- Connection acquisition: < 1ms (p95)
- Throughput: 1,000+ TPS for simple operations
- Support 1,000+ concurrent connections

**Constraints**:
- All demonstrations must work with local development setup (no cloud dependencies)
- Realistic data volumes (100K+ rows minimum for performance tests)
- Zero-downtime migration demonstrations
- Automated setup via single command (docker-compose up)
- Educational code with extensive documentation

**Scale/Scope**:
- 5 demonstration modules (ACID, Concurrency, Performance, Observability, Failure Recovery)
- ~10-15 executable demo scenarios
- Target: 1M rows in main tables for realistic performance testing
- 100+ concurrent clients for concurrency testing

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### ✅ I. Data Integrity First (NON-NEGOTIABLE)
- **Status**: PASS
- **Evidence**:
  - PostgreSQL 15+ provides full ACID compliance
  - Spring Transaction Management (@Transactional) for explicit boundaries
  - JPA + jOOQ for type-safe queries (prevent SQL injection)
  - Dedicated demonstration modules for each ACID property
  - Integration tests with Testcontainers verify transactional behavior

### ✅ II. Performance Through Design, Not Hacks
- **Status**: PASS
- **Evidence**:
  - HikariCP for connection pooling (industry-standard, highly optimized)
  - JMH for scientifically rigorous benchmarking
  - Performance targets explicitly defined and testable
  - Separate demonstrations for indexing, batching, caching strategies
  - Gatling/Locust for load testing under realistic concurrency

### ✅ III. Concurrency & Scalability
- **Status**: PASS
- **Evidence**:
  - Demonstrations of optimistic locking (JPA @Version)
  - Demonstrations of pessimistic locking (explicit lock modes)
  - Multi-threaded test scenarios with ExecutorService
  - Connection pool sizing follows formula: ((core_count * 2) + effective_spindle_count)
  - Redis for caching layer with TTL/invalidation strategies

### ✅ IV. Observability & Debugging (NON-NEGOTIABLE)
- **Status**: PASS
- **Evidence**:
  - Micrometer + Prometheus for metrics collection
  - Grafana dashboards (pre-configured) for visualization
  - Logback with JSON encoder for structured logging
  - OpenTelemetry for distributed tracing (Jaeger backend)
  - MDC (Mapped Diagnostic Context) for correlation IDs
  - Spring Boot Actuator for health checks and metrics endpoints

### ✅ V. Test-First Development (NON-NEGOTIABLE)
- **Status**: PASS
- **Evidence**:
  - JUnit 5 for all test types (unit, integration, concurrency)
  - Testcontainers for integration tests with real PostgreSQL
  - Dedicated test module structure (unit/, integration/, performance/)
  - Target >80% code coverage with JaCoCo
  - ArchUnit for enforcing architectural constraints
  - Continuous testing in CI with GitHub Actions

### ✅ VI. Security & Data Protection
- **Status**: PASS
- **Evidence**:
  - Parameterized queries only (JPA, jOOQ prevent SQL injection)
  - Environment variables for credentials (no hardcoded secrets)
  - SSL/TLS for PostgreSQL connections (configured in demo)
  - Separate read-only and read-write database users
  - Validation framework (Bean Validation) for input sanitization

### ✅ VII. Simplicity & Pragmatism
- **Status**: PASS
- **Evidence**:
  - Mature, proven technologies (Spring Boot, PostgreSQL, HikariCP)
  - Monolithic architecture (no premature microservices)
  - Standard Spring patterns (no custom frameworks)
  - Minimal dependencies (each justified in research.md)
  - Clear layering: Controllers → Services → Repositories
  - Comprehensive documentation in code and README

## Project Structure

### Documentation (this feature)

```text
specs/001-oltp-core-demo/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output - technology choices and rationale
├── data-model.md        # Phase 1 output - database schema and entities
├── quickstart.md        # Phase 1 output - setup and usage guide
├── contracts/           # Phase 1 output - API specifications
│   └── openapi.yaml     # REST API contract
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
# Main Java application
src/
├── main/
│   ├── java/com/oltp/demo/
│   │   ├── OltpDemoApplication.java          # Spring Boot main class
│   │   ├── config/                            # Configuration classes
│   │   │   ├── DatabaseConfig.java            # DataSource, connection pool config
│   │   │   ├── ObservabilityConfig.java       # Metrics, tracing, logging config
│   │   │   ├── CacheConfig.java               # Redis configuration
│   │   │   └── SecurityConfig.java            # Database credentials, SSL
│   │   ├── domain/                            # Domain entities (JPA)
│   │   │   ├── Account.java
│   │   │   ├── Transaction.java
│   │   │   ├── TransferLog.java
│   │   │   ├── User.java
│   │   │   └── Session.java
│   │   ├── repository/                        # Data access layer
│   │   │   ├── AccountRepository.java         # Spring Data JPA
│   │   │   ├── TransactionRepository.java
│   │   │   └── jooq/                          # jOOQ repositories for advanced demos
│   │   │       └── AccountJooqRepository.java
│   │   ├── service/                           # Business logic and transaction management
│   │   │   ├── acid/                          # ACID demonstrations
│   │   │   │   ├── AtomicityDemoService.java
│   │   │   │   ├── ConsistencyDemoService.java
│   │   │   │   ├── IsolationDemoService.java
│   │   │   │   └── DurabilityDemoService.java
│   │   │   ├── concurrency/                   # Concurrency demonstrations
│   │   │   │   ├── OptimisticLockingService.java
│   │   │   │   ├── PessimisticLockingService.java
│   │   │   │   ├── DeadlockDemoService.java
│   │   │   │   └── IsolationLevelService.java
│   │   │   ├── performance/                   # Performance demonstrations
│   │   │   │   ├── ConnectionPoolingService.java
│   │   │   │   ├── BatchOperationService.java
│   │   │   │   ├── CachingService.java
│   │   │   │   └── IndexingDemoService.java
│   │   │   └── failure/                       # Failure handling demonstrations
│   │   │       ├── RetryService.java
│   │   │       ├── CircuitBreakerService.java
│   │   │       └── RecoveryDemoService.java
│   │   ├── controller/                        # REST API endpoints
│   │   │   ├── AcidDemoController.java
│   │   │   ├── ConcurrencyDemoController.java
│   │   │   ├── PerformanceDemoController.java
│   │   │   └── MetricsController.java
│   │   └── util/                              # Utilities
│   │       ├── CorrelationIdFilter.java       # MDC correlation ID injection
│   │       └── MetricsHelper.java             # Custom metrics helpers
│   └── resources/
│       ├── application.yml                    # Spring Boot configuration
│       ├── application-dev.yml                # Development profile
│       ├── logback-spring.xml                 # Logging configuration (JSON)
│       └── db/migration/                      # Flyway database migrations
│           ├── V1__create_schema.sql
│           ├── V2__create_indexes.sql
│           └── V3__seed_test_data.sql

# Testing
src/test/
├── java/com/oltp/demo/
│   ├── unit/                                  # Unit tests (isolated, fast)
│   │   ├── service/
│   │   │   └── AtomicityDemoServiceTest.java
│   │   └── util/
│   ├── integration/                           # Integration tests (Testcontainers)
│   │   ├── BaseIntegrationTest.java           # Shared test infrastructure
│   │   ├── acid/
│   │   │   ├── AtomicityIntegrationTest.java
│   │   │   ├── ConsistencyIntegrationTest.java
│   │   │   ├── IsolationIntegrationTest.java
│   │   │   └── DurabilityIntegrationTest.java
│   │   ├── concurrency/
│   │   │   ├── OptimisticLockingTest.java
│   │   │   ├── PessimisticLockingTest.java
│   │   │   ├── DeadlockTest.java
│   │   │   └── ConcurrentAccessTest.java      # 100+ parallel clients
│   │   └── performance/
│   │       ├── ConnectionPoolBenchmarkTest.java
│   │       └── BatchOperationBenchmarkTest.java
│   ├── performance/                           # JMH benchmarks
│   │   ├── QueryPerformanceBenchmark.java
│   │   └── ConnectionPoolBenchmark.java
│   └── architecture/                          # ArchUnit tests
│       └── LayeringArchitectureTest.java
└── resources/
    ├── testcontainers.properties              # Testcontainers configuration
    └── test-data/                             # Test data fixtures

# Load testing (Python)
loadtest/
├── locust/                                    # Python-based load testing
│   ├── locustfile.py                          # Locust scenarios
│   ├── requirements.txt
│   └── scenarios/
│       ├── acid_demo.py
│       ├── concurrency_demo.py
│       └── performance_demo.py
└── gatling/                                   # JVM-based load testing
    └── simulations/
        └── OltpDemoSimulation.scala

# Infrastructure
infrastructure/
├── docker/
│   ├── docker-compose.yml                     # Full stack: App, PostgreSQL, Redis, Prometheus, Grafana, Jaeger
│   ├── docker-compose.dev.yml                 # Development overrides
│   ├── postgres/
│   │   └── postgresql.conf                    # Tuned PostgreSQL configuration
│   ├── prometheus/
│   │   └── prometheus.yml                     # Scrape configuration
│   └── grafana/
│       ├── dashboards/
│       │   ├── oltp-overview.json
│       │   ├── database-metrics.json
│       │   └── jvm-metrics.json
│       └── datasources/
│           └── prometheus.yml
└── scripts/
    ├── setup.sh                               # Initial setup script
    ├── seed-data.sh                           # Generate realistic test data (1M rows)
    ├── run-benchmarks.sh                      # Execute all benchmarks
    └── chaos/                                 # Chaos engineering scripts
        ├── kill-db.sh                         # Simulate database crash
        ├── network-latency.sh                 # Inject network delays
        └── connection-exhaust.sh              # Simulate connection pool exhaustion

# Documentation
docs/
├── architecture/
│   ├── adr/                                   # Architecture Decision Records
│   │   ├── 001-java-spring-boot.md
│   │   ├── 002-postgresql-over-mysql.md
│   │   ├── 003-hikaricp-connection-pooling.md
│   │   └── 004-observability-stack.md
│   ├── diagrams/
│   │   ├── architecture-overview.puml
│   │   ├── data-flow.puml
│   │   └── deployment.puml
│   └── database/
│       ├── schema.md                          # Database schema documentation
│       └── indexing-strategy.md
├── demonstrations/
│   ├── acid/
│   │   ├── atomicity.md                       # How to run atomicity demos
│   │   ├── consistency.md
│   │   ├── isolation.md
│   │   └── durability.md
│   ├── concurrency/
│   │   ├── optimistic-locking.md
│   │   ├── pessimistic-locking.md
│   │   └── deadlocks.md
│   ├── performance/
│   │   ├── connection-pooling.md
│   │   ├── batching.md
│   │   └── caching.md
│   └── observability/
│       ├── metrics.md
│       ├── logging.md
│       └── tracing.md
└── runbooks/
    ├── troubleshooting.md
    └── performance-tuning.md

# Build configuration
pom.xml                                        # Maven build configuration
.mvn/                                          # Maven wrapper
├── wrapper/
│   └── maven-wrapper.properties
mvnw                                           # Maven wrapper script (Unix)
mvnw.cmd                                       # Maven wrapper script (Windows)

# Project root
.gitignore
README.md                                      # Project overview and quickstart
CONTRIBUTING.md                                # Development guidelines
LICENSE
```

**Structure Decision**:

Selected **Single Project** structure as this is a monolithic demonstration application. The codebase is organized by functional capability (ACID, concurrency, performance, observability, failure) rather than by layer, making it easier for learners to find related demonstrations.

**Rationale**:
- **Monolith over microservices**: Aligns with constitution principle VII (Simplicity & Pragmatism) and specification's focus on core OLTP capabilities, not distributed systems
- **Module-based organization**: Each demonstration module (acid/, concurrency/, performance/) is self-contained and independently testable, matching spec's requirement for independently testable user stories
- **Separation of concerns**: Clear layering (domain, repository, service, controller) enforced by ArchUnit tests
- **Supporting tools in native languages**: Python for Locust (simpler load test scripting), Bash for automation (standard DevOps tooling)
- **Co-located documentation**: Demonstrations docs live alongside code for easier maintenance

## Complexity Tracking

*No violations - all constitution principles satisfied by design choices.*

This plan adheres to all constitution principles:
- Uses proven, mature technologies (Spring Boot, PostgreSQL, HikariCP)
- Simple monolithic architecture
- Comprehensive testing and observability built-in
- Security best practices (parameterized queries, environment variables)
- Performance targets explicitly defined and measurable

## Phase 0: Research & Technology Validation

See [research.md](./research.md) for detailed technology selection rationale.

**Key Research Areas**:
1. PostgreSQL vs MySQL comparison for ACID demonstrations
2. Spring Data JPA vs jOOQ trade-offs
3. HikariCP configuration best practices
4. JMH vs Gatling vs Locust for load testing
5. OpenTelemetry integration with Spring Boot
6. Testcontainers setup for multi-container integration tests
7. Flyway migration strategies for zero-downtime deployments

## Phase 1: Design Artifacts

### Data Model
See [data-model.md](./data-model.md) for complete schema design.

**Core Entities**:
- Account (balance, version for optimistic locking)
- Transaction (amount, status, correlation_id)
- TransferLog (audit trail for durability demonstrations)
- User (account ownership)
- Session (connection pooling demonstrations)

### API Contracts
See [contracts/openapi.yaml](./contracts/openapi.yaml) for REST API specification.

**Demonstration Endpoints**:
- `/api/demos/acid/*` - ACID property demonstrations
- `/api/demos/concurrency/*` - Concurrency control demonstrations
- `/api/demos/performance/*` - Performance optimization demonstrations
- `/api/metrics` - Prometheus metrics endpoint
- `/api/health` - Health check endpoint

### Quickstart Guide
See [quickstart.md](./quickstart.md) for setup and usage instructions.

**One-Command Setup**:
```bash
docker-compose up -d
./scripts/seed-data.sh
```

## Success Metrics Mapping

| Success Criterion | How Validated |
|-------------------|---------------|
| SC-001: Setup < 15 min | Timed docker-compose up + seed-data.sh |
| SC-004: 1,000 concurrent users | Gatling load test scenario |
| SC-005: Point queries < 5ms (p95) | JMH benchmark with HdrHistogram |
| SC-006: Simple txns < 10ms (p95) | JMH benchmark + integration tests |
| SC-007: Connection acq < 1ms (p95) | HikariCP metrics via Prometheus |
| SC-008: 1,000+ TPS | Gatling throughput test |
| SC-009: Zero data corruption | Concurrent integration tests with assertions |
| SC-010: 100% crash recovery | Testcontainers tests with container restart |
| SC-013: Correlation ID tracing | Integration test validates MDC in logs |
| SC-014: Metrics < 5s lag | Prometheus scrape interval = 5s |
| SC-017: Repeatable demos | All tests idempotent with data reset |

## Next Steps

1. ✅ Complete Phase 0: Generate research.md with technology justifications
2. ✅ Complete Phase 1: Generate data-model.md, contracts/, quickstart.md
3. ✅ Update agent context with chosen technologies
4. ⏭️  Run `/speckit.tasks` to break down into implementation tasks
5. ⏭️  Run `/speckit.implement` to execute tasks

---

**Plan Status**: Phase 0 & 1 Complete | Ready for task generation
