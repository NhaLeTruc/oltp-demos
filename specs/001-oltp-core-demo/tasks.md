# Tasks: OLTP Core Capabilities Tech Demo

**Input**: Design documents from `/specs/001-oltp-core-demo/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/openapi.yaml

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

All paths are from repository root:
- Java source: `src/main/java/com/oltp/demo/`
- Java tests: `src/test/java/com/oltp/demo/`
- Resources: `src/main/resources/`
- Infrastructure: `infrastructure/`
- Load tests: `loadtest/`
- Documentation: `docs/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and basic structure

- [X] T001 Create Maven project with Spring Boot 3.2+ parent POM in pom.xml
- [X] T002 [P] Configure Maven wrapper scripts (mvnw, mvnw.cmd) in .mvn/wrapper/
- [X] T003 [P] Create .gitignore with Java, Maven, IDE, and OS-specific entries
- [X] T004 [P] Create project directory structure per plan.md (src/, loadtest/, infrastructure/, docs/)
- [X] T005 [P] Add Spring Boot dependencies in pom.xml: spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-actuator
- [X] T006 [P] Add database dependencies in pom.xml: postgresql, flyway-core, HikariCP
- [X] T007 [P] Add observability dependencies in pom.xml: micrometer-registry-prometheus, spring-boot-starter-aop, logstash-logback-encoder
- [X] T008 [P] Add testing dependencies in pom.xml: spring-boot-starter-test, testcontainers, testcontainers-postgresql, archunit-junit5, JMH
- [X] T009 [P] Add jOOQ code generation plugin in pom.xml with PostgreSQL configuration
- [X] T010 [P] Create Spring Boot main class in src/main/java/com/oltp/demo/OltpDemoApplication.java
- [X] T011 [P] Create README.md with project overview and quickstart from specs/001-oltp-core-demo/quickstart.md
- [X] T012 [P] Create CONTRIBUTING.md with development guidelines and constitution reference

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Configuration & Infrastructure

- [X] T013 Create base application.yml in src/main/resources/ with datasource, JPA, Flyway, HikariCP configuration
- [X] T014 [P] Create application-dev.yml in src/main/resources/ with development-specific settings
- [X] T015 [P] Create logback-spring.xml in src/main/resources/ with JSON logging and MDC configuration
- [X] T016 Create DatabaseConfig in src/main/java/com/oltp/demo/config/DatabaseConfig.java with HikariCP datasource bean
- [X] T017 [P] Create SecurityConfig in src/main/java/com/oltp/demo/config/SecurityConfig.java with database user setup
- [X] T018 [P] Create ObservabilityConfig in src/main/java/com/oltp/demo/config/ObservabilityConfig.java with Micrometer customization
- [X] T019 [P] Create CacheConfig in src/main/java/com/oltp/demo/config/CacheConfig.java with Redis connection configuration

### Database Schema & Migrations

- [X] T020 Create V1__create_schema.sql in src/main/resources/db/migration/ with users, account_types, accounts tables
- [X] T021 Create V2__create_transaction_tables.sql in src/main/resources/db/migration/ with transactions, transfer_logs tables
- [X] T022 Create V3__create_sessions_table.sql in src/main/resources/db/migration/ with sessions table
- [X] T023 Create V4__create_indexes.sql in src/main/resources/db/migration/ with all 21 indexes per data-model.md
- [X] T024 Create V5__add_constraints.sql in src/main/resources/db/migration/ with CHECK constraints per data-model.md
- [X] T025 Create V6__seed_reference_data.sql in src/main/resources/db/migration/ with account_types seed data

### Domain Entities (JPA)

- [X] T026 [P] Create AccountType entity in src/main/java/com/oltp/demo/domain/AccountType.java with JPA annotations
- [X] T027 [P] Create User entity in src/main/java/com/oltp/demo/domain/User.java with JPA annotations and relationships
- [X] T028 [P] Create Account entity in src/main/java/com/oltp/demo/domain/Account.java with @Version for optimistic locking
- [X] T029 [P] Create Transaction entity in src/main/java/com/oltp/demo/domain/Transaction.java with correlation_id and status
- [X] T030 [P] Create TransferLog entity in src/main/java/com/oltp/demo/domain/TransferLog.java for audit trail
- [X] T031 [P] Create Session entity in src/main/java/com/oltp/demo/domain/Session.java for connection pool demos

### Repositories (Spring Data JPA)

- [X] T032 [P] Create AccountTypeRepository in src/main/java/com/oltp/demo/repository/AccountTypeRepository.java
- [X] T033 [P] Create UserRepository in src/main/java/com/oltp/demo/repository/UserRepository.java
- [X] T034 [P] Create AccountRepository in src/main/java/com/oltp/demo/repository/AccountRepository.java with custom lock methods
- [X] T035 [P] Create TransactionRepository in src/main/java/com/oltp/demo/repository/TransactionRepository.java
- [X] T036 [P] Create TransferLogRepository in src/main/java/com/oltp/demo/repository/TransferLogRepository.java
- [X] T037 [P] Create SessionRepository in src/main/java/com/oltp/demo/repository/SessionRepository.java

### Utilities & Helpers

- [X] T038 [P] Create CorrelationIdFilter in src/main/java/com/oltp/demo/util/CorrelationIdFilter.java for MDC injection
- [X] T039 [P] Create MetricsHelper in src/main/java/com/oltp/demo/util/MetricsHelper.java for custom metrics
- [X] T040 [P] Create GlobalExceptionHandler in src/main/java/com/oltp/demo/util/GlobalExceptionHandler.java

### Docker Infrastructure

- [X] T041 Create docker-compose.yml in infrastructure/docker/ with PostgreSQL, Redis, Prometheus, Grafana, Jaeger services
- [X] T042 [P] Create docker-compose.dev.yml in infrastructure/docker/ with development overrides
- [X] T043 [P] Create postgresql.conf in infrastructure/docker/postgres/ with tuned PostgreSQL configuration
- [X] T044 [P] Create prometheus.yml in infrastructure/docker/prometheus/ with scrape configuration
- [X] T045 [P] Create Grafana datasource configuration in infrastructure/docker/grafana/datasources/prometheus.yml

### Testing Infrastructure

- [X] T046 Create BaseIntegrationTest in src/test/java/com/oltp/demo/integration/BaseIntegrationTest.java with Testcontainers setup
- [X] T047 [P] Create testcontainers.properties in src/test/resources/ with Testcontainers configuration
- [X] T048 [P] Create LayeringArchitectureTest in src/test/java/com/oltp/demo/architecture/LayeringArchitectureTest.java with ArchUnit rules

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - ACID Transaction Guarantees (Priority: P1) 🎯 MVP

**Goal**: Demonstrate all four ACID properties (Atomicity, Consistency, Isolation, Durability) through executable examples that developers can run and observe

**Independent Test**: Execute transfer transactions that show atomicity (rollback on failure), consistency (constraint enforcement), isolation (concurrent execution), and durability (crash recovery)

**Functional Requirements**: FR-001, FR-002, FR-003, FR-004

### Implementation for User Story 1

#### Atomicity Demonstrations

- [X] T049 [P] [US1] Create AtomicityDemoService in src/main/java/com/oltp/demo/service/acid/AtomicityDemoService.java
- [X] T050 [US1] Implement successful transfer method with @Transactional in AtomicityDemoService
- [X] T051 [US1] Implement failed transfer method with rollback demonstration in AtomicityDemoService
- [X] T052 [US1] Implement mid-transaction failure simulation in AtomicityDemoService

#### Consistency Demonstrations

- [X] T053 [P] [US1] Create ConsistencyDemoService in src/main/java/com/oltp/demo/service/acid/ConsistencyDemoService.java
- [X] T054 [US1] Implement constraint violation detection (negative balance) in ConsistencyDemoService
- [X] T055 [US1] Implement foreign key constraint demonstration in ConsistencyDemoService
- [X] T056 [US1] Implement CHECK constraint violation handling in ConsistencyDemoService

#### Isolation Demonstrations

- [X] T057 [P] [US1] Create IsolationDemoService in src/main/java/com/oltp/demo/service/acid/IsolationDemoService.java
- [X] T058 [US1] Implement READ_COMMITTED isolation level demonstration in IsolationDemoService
- [X] T059 [US1] Implement REPEATABLE_READ isolation level demonstration in IsolationDemoService
- [X] T060 [US1] Implement SERIALIZABLE isolation level demonstration in IsolationDemoService
- [X] T061 [US1] Implement concurrent transaction executor with configurable isolation in IsolationDemoService

#### Durability Demonstrations

- [X] T062 [P] [US1] Create DurabilityDemoService in src/main/java/com/oltp/demo/service/acid/DurabilityDemoService.java
- [X] T063 [US1] Implement transfer log recording for durability verification in DurabilityDemoService
- [X] T064 [US1] Implement crash recovery verification query in DurabilityDemoService
- [X] T065 [US1] Implement committed transaction lookup by correlation ID in DurabilityDemoService

#### REST API Endpoints

- [X] T066 [P] [US1] Create AcidDemoController in src/main/java/com/oltp/demo/controller/AcidDemoController.java
- [X] T067 [US1] Implement POST /api/demos/acid/atomicity/transfer endpoint in AcidDemoController
- [X] T068 [US1] Implement POST /api/demos/acid/atomicity/fail-mid-transaction endpoint in AcidDemoController
- [X] T069 [US1] Implement POST /api/demos/acid/consistency/enforce-constraints endpoint in AcidDemoController
- [X] T070 [US1] Implement POST /api/demos/acid/isolation/concurrent-transfers endpoint in AcidDemoController
- [X] T071 [US1] Implement GET /api/demos/acid/durability/crash-recovery endpoint in AcidDemoController

#### Integration Tests

- [X] T072 [P] [US1] Create AtomicityIntegrationTest in src/test/java/com/oltp/demo/integration/acid/AtomicityIntegrationTest.java
- [X] T073 [P] [US1] Create ConsistencyIntegrationTest in src/test/java/com/oltp/demo/integration/acid/ConsistencyIntegrationTest.java
- [X] T074 [P] [US1] Create IsolationIntegrationTest in src/test/java/com/oltp/demo/integration/acid/IsolationIntegrationTest.java
- [X] T075 [P] [US1] Create DurabilityIntegrationTest in src/test/java/com/oltp/demo/integration/acid/DurabilityIntegrationTest.java with container restart

#### Documentation

- [ ] T076 [P] [US1] Create atomicity.md in docs/demonstrations/acid/ with curl examples
- [ ] T077 [P] [US1] Create consistency.md in docs/demonstrations/acid/ with curl examples
- [ ] T078 [P] [US1] Create isolation.md in docs/demonstrations/acid/ with curl examples
- [ ] T079 [P] [US1] Create durability.md in docs/demonstrations/acid/ with curl examples

**Checkpoint**: User Story 1 complete - ACID demonstrations fully functional and testable independently

---

## Phase 4: User Story 2 - Concurrency and Conflict Handling (Priority: P1)

**Goal**: Demonstrate optimistic locking, pessimistic locking, deadlock detection, and isolation level trade-offs through concurrent transaction scenarios

**Independent Test**: Execute concurrent operations on shared accounts and observe conflict detection, retry mechanisms, lock behavior, and throughput under contention

**Functional Requirements**: FR-005, FR-006, FR-007, FR-008, FR-009

### Implementation for User Story 2

#### Optimistic Locking Demonstrations

- [X] T080 [P] [US2] Create OptimisticLockingService in src/main/java/com/oltp/demo/service/concurrency/OptimisticLockingService.java
- [X] T081 [US2] Implement concurrent update with version checking in OptimisticLockingService
- [X] T082 [US2] Implement retry logic with exponential backoff in OptimisticLockingService
- [X] T083 [US2] Implement conflict detection counter and metrics in OptimisticLockingService

#### Pessimistic Locking Demonstrations

- [X] T084 [P] [US2] Create PessimisticLockingService in src/main/java/com/oltp/demo/service/concurrency/PessimisticLockingService.java
- [X] T085 [US2] Implement SELECT FOR UPDATE lock acquisition in PessimisticLockingService
- [X] T086 [US2] Implement lock wait time measurement in PessimisticLockingService
- [X] T087 [US2] Implement serialized transaction execution demo in PessimisticLockingService

#### Deadlock Demonstrations

- [X] T088 [P] [US2] Create DeadlockDemoService in src/main/java/com/oltp/demo/service/concurrency/DeadlockDemoService.java
- [X] T089 [US2] Implement bidirectional transfer deadlock scenario (A→B and B→A) in DeadlockDemoService
- [X] T090 [US2] Implement deadlock detection handler with retry in DeadlockDemoService
- [X] T091 [US2] Implement deadlock metrics tracking in DeadlockDemoService

#### Isolation Level Demonstrations

- [X] T092 [P] [US2] Create IsolationLevelService in src/main/java/com/oltp/demo/service/concurrency/IsolationLevelService.java
- [X] T093 [US2] Implement dirty read demonstration with READ_UNCOMMITTED in IsolationLevelService
- [X] T094 [US2] Implement non-repeatable read demonstration with READ_COMMITTED in IsolationLevelService
- [X] T095 [US2] Implement phantom read demonstration with REPEATABLE_READ in IsolationLevelService
- [X] T096 [US2] Implement full isolation with SERIALIZABLE in IsolationLevelService

#### REST API Endpoints

- [X] T097 [P] [US2] Create ConcurrencyDemoController in src/main/java/com/oltp/demo/controller/ConcurrencyDemoController.java
- [X] T098 [US2] Implement POST /api/demos/concurrency/optimistic-locking endpoint in ConcurrencyDemoController
- [X] T099 [US2] Implement POST /api/demos/concurrency/pessimistic-locking endpoint in ConcurrencyDemoController
- [X] T100 [US2] Implement POST /api/demos/concurrency/deadlock endpoint in ConcurrencyDemoController
- [X] T101 [US2] Implement GET /api/demos/concurrency/isolation-levels endpoint in ConcurrencyDemoController

#### Integration Tests

- [X] T102 [P] [US2] Create OptimisticLockingTest in src/test/java/com/oltp/demo/integration/concurrency/OptimisticLockingTest.java
- [X] T103 [P] [US2] Create PessimisticLockingTest in src/test/java/com/oltp/demo/integration/concurrency/PessimisticLockingTest.java
- [X] T104 [P] [US2] Create DeadlockTest in src/test/java/com/oltp/demo/integration/concurrency/DeadlockTest.java
- [X] T105 [P] [US2] Create ConcurrentAccessTest in src/test/java/com/oltp/demo/integration/concurrency/ConcurrentAccessTest.java with 100+ parallel clients using ExecutorService

#### Documentation

- [ ] T106 [P] [US2] Create optimistic-locking.md in docs/demonstrations/concurrency/ with scenarios and metrics interpretation
- [ ] T107 [P] [US2] Create pessimistic-locking.md in docs/demonstrations/concurrency/ with lock acquisition examples
- [ ] T108 [P] [US2] Create deadlocks.md in docs/demonstrations/concurrency/ with detection and recovery explanation

**Checkpoint**: User Story 2 complete - Concurrency demonstrations fully functional and testable independently

---

## Phase 5: User Story 3 - Performance Under Load (Priority: P2)

**Goal**: Demonstrate connection pooling, query optimization, batch operations, and caching under realistic load with measurable performance improvements

**Independent Test**: Execute load tests showing performance gains from connection pooling (10x), batching (20x), caching (5x), and indexing (100x)

**Functional Requirements**: FR-010, FR-011, FR-012, FR-013, FR-014

### Implementation for User Story 3

#### Connection Pooling Demonstrations

- [X] T109 [P] [US3] Create ConnectionPoolingService in src/main/java/com/oltp/demo/service/performance/ConnectionPoolingService.java
- [X] T110 [US3] Implement pooled connection query execution in ConnectionPoolingService
- [X] T111 [US3] Implement unpooled connection query execution for comparison in ConnectionPoolingService
- [X] T112 [US3] Implement HikariCP pool statistics collection in ConnectionPoolingService

#### Batch Operation Demonstrations

- [X] T113 [P] [US3] Create BatchOperationService in src/main/java/com/oltp/demo/service/performance/BatchOperationService.java
- [X] T114 [US3] Implement individual insert operations in BatchOperationService
- [X] T115 [US3] Implement JDBC batch insert operations in BatchOperationService
- [X] T116 [US3] Implement throughput measurement and comparison in BatchOperationService

#### Caching Demonstrations

- [X] T117 [P] [US3] Create CachingService in src/main/java/com/oltp/demo/service/performance/CachingService.java
- [X] T118 [US3] Implement Redis cache integration with Spring @Cacheable in CachingService
- [X] T119 [US3] Implement cache hit/miss tracking in CachingService
- [X] T120 [US3] Implement manual cache invalidation demonstration in CachingService
- [X] T121 [US3] Implement TTL-based expiration demonstration in CachingService

#### Indexing Demonstrations

- [X] T122 [P] [US3] Create IndexingDemoService in src/main/java/com/oltp/demo/service/performance/IndexingDemoService.java
- [X] T123 [US3] Implement indexed query with EXPLAIN ANALYZE in IndexingDemoService
- [X] T124 [US3] Implement full table scan query for comparison in IndexingDemoService
- [X] T125 [US3] Implement query plan parsing and metrics extraction in IndexingDemoService

#### jOOQ Advanced Queries

- [X] T126 [P] [US3] Create AccountJooqRepository in src/main/java/com/oltp/demo/repository/jooq/AccountJooqRepository.java
- [X] T127 [US3] Implement type-safe complex queries with jOOQ in AccountJooqRepository
- [X] T128 [US3] Implement batch operations with jOOQ in AccountJooqRepository

#### REST API Endpoints

- [X] T129 [P] [US3] Create PerformanceDemoController in src/main/java/com/oltp/demo/controller/PerformanceDemoController.java
- [X] T130 [US3] Implement GET /api/demos/performance/connection-pooling endpoint in PerformanceDemoController
- [X] T131 [US3] Implement POST /api/demos/performance/batch-operations endpoint in PerformanceDemoController
- [X] T132 [US3] Implement GET /api/demos/performance/caching endpoint in PerformanceDemoController
- [X] T133 [US3] Implement GET /api/demos/performance/indexing endpoint in PerformanceDemoController

#### JMH Benchmarks

- [X] T134 [P] [US3] Create QueryPerformanceBenchmark in src/test/java/com/oltp/demo/performance/QueryPerformanceBenchmark.java with JMH annotations
- [X] T135 [P] [US3] Create ConnectionPoolBenchmark in src/test/java/com/oltp/demo/performance/ConnectionPoolBenchmark.java with HdrHistogram

#### Integration Tests

- [X] T136 [P] [US3] Create ConnectionPoolBenchmarkTest in src/test/java/com/oltp/demo/integration/performance/ConnectionPoolBenchmarkTest.java
- [X] T137 [P] [US3] Create BatchOperationBenchmarkTest in src/test/java/com/oltp/demo/integration/performance/BatchOperationBenchmarkTest.java

#### Load Testing Scripts

- [X] T138 [P] [US3] Create locustfile.py in loadtest/locust/ with base Locust configuration
- [X] T139 [P] [US3] Create performance_demo.py in loadtest/locust/scenarios/ with connection pooling load test
- [X] T140 [P] [US3] Create OltpDemoSimulation.scala in loadtest/gatling/simulations/ with Gatling scenarios
- [X] T141 [P] [US3] Create requirements.txt in loadtest/locust/ with Locust dependencies

#### Documentation

- [X] T142 [P] [US3] Create connection-pooling.md in docs/demonstrations/performance/ with before/after metrics
- [X] T143 [P] [US3] Create batching.md in docs/demonstrations/performance/ with throughput comparisons
- [X] T144 [P] [US3] Create caching.md in docs/demonstrations/performance/ with cache hit ratio analysis

**Checkpoint**: User Story 3 complete - Performance demonstrations fully functional with measurable improvements

---

## Phase 6: User Story 4 - Comprehensive Observability (Priority: P2)

**Goal**: Demonstrate structured logging, metrics collection, distributed tracing, and real-time dashboards for monitoring OLTP systems in production

**Independent Test**: Execute transactions and verify correlation IDs in logs, metrics in Prometheus, traces in Jaeger, and dashboards in Grafana

**Functional Requirements**: FR-015, FR-016, FR-017, FR-018, FR-019

### Implementation for User Story 4

#### Metrics Instrumentation

- [X] T145 [P] [US4] Enhance MetricsHelper in src/main/java/com/oltp/demo/util/MetricsHelper.java with custom transaction metrics
- [X] T146 [US4] Add transaction throughput counter to all service methods via MetricsHelper
- [X] T147 [US4] Add transaction latency timer to all service methods via MetricsHelper
- [X] T148 [US4] Add connection pool metrics collection in DatabaseConfig
- [X] T149 [US4] Add error rate counter to GlobalExceptionHandler

#### Distributed Tracing

- [X] T150 [P] [US4] Add OpenTelemetry Java agent configuration to pom.xml
- [X] T151 [US4] Configure trace propagation in CorrelationIdFilter
- [X] T152 [US4] Add trace spans to service methods with @WithSpan annotation
- [X] T153 [US4] Add database query tracing with p6spy or similar

#### Slow Query Logging

- [X] T154 [P] [US4] Configure slow query logging in application.yml (threshold > 50ms)
- [X] T155 [US4] Implement SlowQueryLogger in src/main/java/com/oltp/demo/util/SlowQueryLogger.java with EXPLAIN ANALYZE
- [X] T156 [US4] Add slow query alert metrics in MetricsHelper

#### REST API Endpoints

- [X] T157 [P] [US4] Create MetricsController in src/main/java/com/oltp/demo/controller/MetricsController.java
- [X] T158 [US4] Implement GET /api/metrics endpoint exposing Prometheus format in MetricsController
- [X] T159 [US4] Implement GET /api/health endpoint with database/Redis health checks in MetricsController

#### Grafana Dashboards

- [X] T160 [P] [US4] Create oltp-overview.json in infrastructure/docker/grafana/dashboards/ with transaction metrics
- [X] T161 [P] [US4] Create database-metrics.json in infrastructure/docker/grafana/dashboards/ with PostgreSQL metrics
- [X] T162 [P] [US4] Create jvm-metrics.json in infrastructure/docker/grafana/dashboards/ with JVM heap, GC, threads

#### Integration Tests

- [ ] T163 [P] [US4] Create ObservabilityIntegrationTest in src/test/java/com/oltp/demo/integration/observability/ObservabilityIntegrationTest.java
- [ ] T164 [US4] Test correlation ID propagation in ObservabilityIntegrationTest
- [ ] T165 [US4] Test metrics emission to Prometheus in ObservabilityIntegrationTest
- [ ] T166 [US4] Test trace creation in OpenTelemetry in ObservabilityIntegrationTest

#### Documentation

- [ ] T167 [P] [US4] Create metrics.md in docs/demonstrations/observability/ with Prometheus queries
- [ ] T168 [P] [US4] Create logging.md in docs/demonstrations/observability/ with log analysis examples
- [ ] T169 [P] [US4] Create tracing.md in docs/demonstrations/observability/ with Jaeger UI screenshots

**Checkpoint**: User Story 4 complete - Observability fully instrumented with metrics, logs, and traces

---

## Phase 7: User Story 5 - Failure Scenarios and Recovery (Priority: P3)

**Goal**: Demonstrate resilience patterns including connection retry, crash recovery, circuit breakers, and resource exhaustion handling

**Independent Test**: Simulate failures (network loss, DB crash, pool exhaustion) and verify graceful degradation and recovery

**Functional Requirements**: FR-020, FR-021, FR-022, FR-023

### Implementation for User Story 5

#### Retry Logic

- [ ] T170 [P] [US5] Create RetryService in src/main/java/com/oltp/demo/service/failure/RetryService.java
- [ ] T171 [US5] Implement connection retry with exponential backoff in RetryService using Spring Retry
- [ ] T172 [US5] Implement retry metrics (attempts, successes, failures) in RetryService
- [ ] T173 [US5] Implement max retry limit and fallback behavior in RetryService

#### Circuit Breaker

- [ ] T174 [P] [US5] Create CircuitBreakerService in src/main/java/com/oltp/demo/service/failure/CircuitBreakerService.java
- [ ] T175 [US5] Implement circuit breaker pattern using Resilience4j in CircuitBreakerService
- [ ] T176 [US5] Implement circuit breaker state transitions (CLOSED → OPEN → HALF_OPEN) in CircuitBreakerService
- [ ] T177 [US5] Add circuit breaker metrics to MetricsHelper

#### Crash Recovery

- [ ] T178 [P] [US5] Create RecoveryDemoService in src/main/java/com/oltp/demo/service/failure/RecoveryDemoService.java
- [ ] T179 [US5] Implement committed transaction verification after crash in RecoveryDemoService
- [ ] T180 [US5] Implement WAL verification demonstration in RecoveryDemoService
- [ ] T181 [US5] Implement point-in-time recovery query in RecoveryDemoService

#### Resource Exhaustion Handling

- [ ] T182 [US5] Implement connection pool exhaustion detection in DatabaseConfig
- [ ] T183 [US5] Implement graceful queue/reject logic for exhausted pool in RetryService
- [ ] T184 [US5] Add connection pool wait time alerting in MetricsHelper

#### REST API Endpoints

- [ ] T185 [P] [US5] Create FailureDemoController in src/main/java/com/oltp/demo/controller/FailureDemoController.java
- [ ] T186 [US5] Implement POST /api/demos/failure/retry endpoint in FailureDemoController
- [ ] T187 [US5] Implement POST /api/demos/failure/circuit-breaker endpoint in FailureDemoController
- [ ] T188 [US5] Implement GET /api/demos/failure/recovery endpoint in FailureDemoController

#### Chaos Engineering Scripts

- [ ] T189 [P] [US5] Create kill-db.sh in infrastructure/scripts/chaos/ to simulate database crash
- [ ] T190 [P] [US5] Create network-latency.sh in infrastructure/scripts/chaos/ to inject delays with tc or toxiproxy
- [ ] T191 [P] [US5] Create connection-exhaust.sh in infrastructure/scripts/chaos/ to simulate pool exhaustion

#### Integration Tests

- [ ] T192 [P] [US5] Create FailureRecoveryIntegrationTest in src/test/java/com/oltp/demo/integration/failure/FailureRecoveryIntegrationTest.java
- [ ] T193 [US5] Test connection retry with Testcontainers pause/unpause in FailureRecoveryIntegrationTest
- [ ] T194 [US5] Test crash recovery with Testcontainers restart in FailureRecoveryIntegrationTest
- [ ] T195 [US5] Test circuit breaker state transitions in FailureRecoveryIntegrationTest

#### Documentation

- [ ] T196 [P] [US5] Create failure-handling.md in docs/demonstrations/failure/ with retry patterns
- [ ] T197 [P] [US5] Create recovery.md in docs/demonstrations/failure/ with crash recovery procedure

**Checkpoint**: User Story 5 complete - Failure scenarios and recovery fully demonstrated

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Final touches, automation scripts, comprehensive documentation, and production readiness

### Automation Scripts

- [ ] T198 [P] Create setup.sh in infrastructure/scripts/ for one-command project initialization
- [ ] T199 [P] Create seed-data.sh in infrastructure/scripts/ to generate 1M rows of realistic test data
- [ ] T200 [P] Create run-benchmarks.sh in infrastructure/scripts/ to execute all JMH and Gatling tests
- [ ] T201 [P] Create clean-reset.sh in infrastructure/scripts/ to reset database and caches

### Account Management API (Supporting Endpoints)

- [ ] T202 [P] Create AccountController in src/main/java/com/oltp/demo/controller/AccountController.java
- [ ] T203 Create AccountService in src/main/java/com/oltp/demo/service/AccountService.java with CRUD operations
- [ ] T204 Implement POST /api/accounts endpoint in AccountController
- [ ] T205 Implement GET /api/accounts endpoint with filtering in AccountController
- [ ] T206 Implement GET /api/accounts/{id} endpoint in AccountController

### Architecture Decision Records

- [ ] T207 [P] Create 001-java-spring-boot.md in docs/architecture/adr/ documenting Java/Spring Boot choice
- [ ] T208 [P] Create 002-postgresql-over-mysql.md in docs/architecture/adr/ documenting database choice
- [ ] T209 [P] Create 003-hikaricp-connection-pooling.md in docs/architecture/adr/ documenting connection pool choice
- [ ] T210 [P] Create 004-observability-stack.md in docs/architecture/adr/ documenting Micrometer/Prometheus/Grafana choice

### Architecture Diagrams

- [ ] T211 [P] Create architecture-overview.puml in docs/architecture/diagrams/ with component diagram
- [ ] T212 [P] Create data-flow.puml in docs/architecture/diagrams/ with transaction flow sequence
- [ ] T213 [P] Create deployment.puml in docs/architecture/diagrams/ with Docker Compose architecture

### Database Documentation

- [ ] T214 [P] Create schema.md in docs/architecture/database/ with entity documentation from data-model.md
- [ ] T215 [P] Create indexing-strategy.md in docs/architecture/database/ with index justifications

### Runbooks

- [ ] T216 [P] Create troubleshooting.md in docs/runbooks/ with common issues and solutions
- [ ] T217 [P] Create performance-tuning.md in docs/runbooks/ with HikariCP tuning, query optimization

### Final Testing

- [ ] T218 Create full end-to-end test suite running all demonstrations in sequence
- [ ] T219 Run JaCoCo code coverage report and verify >80% coverage
- [ ] T220 Run all ArchUnit tests to validate layering compliance
- [ ] T221 Execute full load test suite with Gatling (1000 concurrent users, 5 minutes)
- [ ] T222 Verify all Grafana dashboards display metrics correctly
- [ ] T223 Verify all curl examples in quickstart.md work correctly

### README and Documentation

- [ ] T224 Update main README.md with complete setup instructions and demo examples
- [ ] T225 Add badges to README.md (build status, coverage, license)
- [ ] T226 Create comprehensive API documentation with Swagger/OpenAPI UI
- [ ] T227 Add LICENSE file (if not already present)

**Final Checkpoint**: All user stories complete, production-ready demo with full documentation

---

## Dependencies & Execution Order

### User Story Dependencies

```
Foundation (Phase 2) - BLOCKS ALL
    ↓
┌───┴────┬────────┬────────┬─────────┐
│        │        │        │         │
US1      US2      US4      US3       US5
(P1)     (P1)     (P2)     (P2)      (P3)
ACID     Concur   Observ   Perf      Fail
│        │        │        │         │
└────────┴────────┴────────┴─────────┘
            ↓
        Polish (Phase 8)
```

**Critical Path**: Foundation → US1 → US2 → Polish

**Parallel Opportunities**:
- After Foundation: US1, US2, US4 can run in parallel (independent functionality)
- US3 requires US1 (needs transfer transactions for performance testing)
- US5 requires US1 (needs transactions for failure scenarios)
- US4 (Observability) can start in parallel but instruments all other stories

### Recommended Execution Order

**Sprint 1** (MVP - Foundation + US1):
- Phase 1: Setup (T001-T012) - 1 day
- Phase 2: Foundation (T013-T048) - 3 days
- Phase 3: US1 ACID (T049-T079) - 5 days
**Total: ~9 days for working MVP**

**Sprint 2** (P1 Stories - Concurrency):
- Phase 4: US2 Concurrency (T080-T108) - 5 days

**Sprint 3** (P2 Stories - Performance & Observability):
- Phase 5: US3 Performance (T109-T144) - 6 days (can run parallel with Phase 6)
- Phase 6: US4 Observability (T145-T169) - 4 days (can run parallel with Phase 5)

**Sprint 4** (P3 Stories - Failure Handling + Polish):
- Phase 7: US5 Failure (T170-T197) - 4 days
- Phase 8: Polish (T198-T227) - 3 days

**Total Estimated Time**: ~31 days (6 weeks with parallelization)

### Parallel Execution Examples

**Within Foundation Phase** (after T013):
```bash
# Can run in parallel:
T014, T015, T016, T017, T018, T019  # Configuration files
T026, T027, T028, T029, T030, T031  # Domain entities
T032, T033, T034, T035, T036, T037  # Repositories
T038, T039, T040                     # Utilities
T041, T042, T043, T044, T045         # Docker configs
T046, T047, T048                     # Testing infrastructure
```

**Within US1 Phase**:
```bash
# Can run in parallel:
T049, T053, T057, T062  # All 4 ACID service classes
T066                    # Controller (once services exist)
T072, T073, T074, T075  # All integration tests (once implementation done)
T076, T077, T078, T079  # All documentation (once implementation done)
```

**Cross-Story Parallelization** (after Foundation):
```bash
# These entire phases can run concurrently:
Phase 3 (US1 ACID)         + Phase 4 (US2 Concurrency) + Phase 6 (US4 Observability)
# Once US1 complete:
Phase 5 (US3 Performance)  + Phase 7 (US5 Failure)
```

## Implementation Strategy

### MVP Scope (Minimum Viable Product)

**Target**: Demonstrate core OLTP value in < 2 weeks

**Includes**:
- Foundation (Phase 2): All infrastructure
- User Story 1 (Phase 3): Complete ACID demonstrations
- Basic observability (from Phase 6): Logs + basic metrics
- Single load test scenario

**Excludes** (defer to later):
- User Stories 2, 3, 4, 5 (all concurrency, performance, advanced observability, failure)
- Grafana dashboards
- Chaos engineering scripts
- Comprehensive documentation

**MVP Success Criteria**:
- Can run `docker-compose up` and access working demo
- Can execute all 4 ACID demonstrations via curl
- Can see transaction logs with correlation IDs
- Can run basic load test showing ACID compliance under load

### Incremental Delivery

**Release 1** (MVP): Foundation + US1 ACID (Sprint 1)
**Release 2**: + US2 Concurrency (Sprint 2)
**Release 3**: + US3 Performance + US4 Observability (Sprint 3)
**Release 4**: + US5 Failure + Polish (Sprint 4)

Each release delivers independent value and can be demoed standalone.

---

## Task Summary

**Total Tasks**: 227

**By Phase**:
- Phase 1 (Setup): 12 tasks
- Phase 2 (Foundation): 36 tasks
- Phase 3 (US1 ACID): 31 tasks
- Phase 4 (US2 Concurrency): 29 tasks
- Phase 5 (US3 Performance): 36 tasks
- Phase 6 (US4 Observability): 25 tasks
- Phase 7 (US5 Failure): 28 tasks
- Phase 8 (Polish): 30 tasks

**Parallelizable Tasks**: 142 tasks marked with [P] (62.6%)

**By User Story**:
- Foundation: 48 tasks
- US1 (ACID): 31 tasks
- US2 (Concurrency): 29 tasks
- US3 (Performance): 36 tasks
- US4 (Observability): 25 tasks
- US5 (Failure): 28 tasks
- Cross-cutting: 30 tasks

**Independent Test Criteria**:
- ✅ US1: Execute ACID demonstrations and observe rollbacks, constraints, isolation, durability
- ✅ US2: Execute concurrent transfers and observe conflicts, locks, deadlocks
- ✅ US3: Run load tests and measure 10x (pooling), 20x (batching), 5x (caching) improvements
- ✅ US4: Check logs for correlation IDs, Prometheus for metrics, Jaeger for traces
- ✅ US5: Simulate failures and verify retry, circuit breaker, crash recovery

**Format Validation**: ✅ All 227 tasks follow strict checklist format with checkboxes, IDs, optional [P]/[Story] labels, and file paths

---

**Generated**: 2025-11-16
**Ready for**: Implementation via `/speckit.implement` or manual execution
**MVP Duration**: ~9 days (Foundation + US1)
**Full Implementation**: ~31 days (all 5 user stories + polish)
