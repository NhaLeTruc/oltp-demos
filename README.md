# OLTP Core Capabilities Tech Demo

![Java](https://img.shields.io/badge/Java-17+-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2+-brightgreen.svg)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15+-blue.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

A production-grade demonstration of Online Transaction Processing (OLTP) patterns and best practices using Java 17+ and Spring Boot 3.2+.

## 🎯 Overview

This project showcases essential OLTP system capabilities through practical, hands-on demonstrations:

- **ACID Transaction Guarantees** - Atomicity, Consistency, Isolation, Durability
- **Concurrency Control** - Optimistic/Pessimistic locking, deadlock handling
- **Performance Optimization** - Connection pooling, batching, caching, indexing
- **Comprehensive Observability** - Metrics, logging, distributed tracing
- **Failure Handling** - Retry logic, circuit breakers, crash recovery

## 🚀 Quick Start

### Prerequisites

- **Java 17+** ([Download](https://adoptium.net/))
- **Docker** & **Docker Compose** ([Download](https://www.docker.com/get-started))
- **Maven 3.8+** (or use included Maven wrapper `./mvnw`)

### One-Command Setup

```bash
# Clone the repository
git clone https://github.com/your-org/oltp-demos.git
cd oltp-demos

# Run automated setup (starts all services and seeds data)
./infrastructure/scripts/setup.sh
```

This script will:
1. ✅ Verify prerequisites (Java, Docker)
2. ✅ Start PostgreSQL, Redis, Prometheus, Grafana, Jaeger (Docker Compose)
3. ✅ Run database migrations (Flyway)
4. ✅ Build the application (Maven)
5. ✅ Seed 1M+ rows of test data

### Manual Setup

```bash
# 1. Start infrastructure services
cd infrastructure/docker
docker-compose up -d
cd ../..

# 2. Build the application
./mvnw clean install

# 3. Run database migrations
./mvnw flyway:migrate

# 4. Seed test data (optional, takes ~5 minutes)
./infrastructure/scripts/seed-data.sh 1000000

# 5. Start the application
./mvnw spring-boot:run
```

### Verify Installation

```bash
# Application health
curl http://localhost:8080/actuator/health

# Swagger UI
open http://localhost:8080/swagger-ui.html

# Grafana dashboards
open http://localhost:3000  # admin/admin

# Jaeger tracing
open http://localhost:16686

# Prometheus metrics
open http://localhost:9090
```

## 📚 Demonstrations

### ACID Properties

**Atomicity** - All-or-nothing transactions:
```bash
curl -X POST http://localhost:8080/api/demos/acid/atomicity/transfer \
  -H 'Content-Type: application/json' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00
  }'
```

**Consistency** - Constraint enforcement:
```bash
curl -X POST http://localhost:8080/api/demos/acid/consistency/enforce-constraints \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "NEGATIVE_BALANCE",
    "accountId": 1,
    "amount": 999999.00
  }'
```

**Isolation** - Concurrent transaction handling:
```bash
curl -X POST http://localhost:8080/api/demos/acid/isolation/non-repeatable-read \
  -H 'Content-Type: application/json' \
  -d '{
    "accountId": 1,
    "isolationLevel": "READ_COMMITTED",
    "sleepBetweenReads": 5000
  }'
```

**Durability** - Crash recovery:
```bash
# Verify WAL configuration
curl http://localhost:8080/api/demos/failure/recovery/wal

# Simulate crash and verify recovery
./infrastructure/scripts/chaos/kill-db.sh --hard-kill
```

### Concurrency Control

**Optimistic Locking**:
```bash
curl -X POST http://localhost:8080/api/demos/concurrency/optimistic/transfer-with-retry \
  -H 'Content-Type: application/json' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00,
    "maxRetries": 3
  }'
```

**Pessimistic Locking**:
```bash
curl -X POST http://localhost:8080/api/demos/concurrency/pessimistic/transfer-with-lock \
  -H 'Content-Type: application/json' \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amount": 100.00,
    "lockType": "PESSIMISTIC_WRITE"
  }'
```

**Deadlock Demonstration**:
```bash
# Terminal 1
curl -X POST http://localhost:8080/api/demos/concurrency/deadlock/create-deadlock \
  -d '{"fromAccountId":1,"toAccountId":2,"amount":100,"delayBetweenLocks":2000}'

# Terminal 2 (within 2 seconds)
curl -X POST http://localhost:8080/api/demos/concurrency/deadlock/create-deadlock \
  -d '{"fromAccountId":2,"toAccountId":1,"amount":50,"delayBetweenLocks":2000}'
```

See [docs/demonstrations/](docs/demonstrations/) for complete demonstration guides with explanations.

## 📖 Documentation

### Architecture

- [Architecture Overview](docs/architecture/diagrams/architecture-overview.puml)
- [Data Flow](docs/architecture/diagrams/data-flow.puml)
- [Deployment Architecture](docs/architecture/diagrams/deployment.puml)

### Architecture Decision Records (ADRs)

- [ADR 001: Java and Spring Boot](docs/architecture/adr/001-java-spring-boot.md)
- [ADR 002: PostgreSQL over MySQL](docs/architecture/adr/002-postgresql-over-mysql.md)
- [ADR 003: HikariCP Connection Pooling](docs/architecture/adr/003-hikaricp-connection-pooling.md)
- [ADR 004: Observability Stack](docs/architecture/adr/004-observability-stack.md)

### Database

- [Schema Documentation](docs/architecture/database/schema.md)
- [Indexing Strategy](docs/architecture/database/indexing-strategy.md)

### Runbooks

- [Troubleshooting Guide](docs/runbooks/troubleshooting.md)
- [Performance Tuning](docs/runbooks/performance-tuning.md)

## 🧪 Testing

### Run All Tests

```bash
# Unit + Integration tests
./mvnw test

# Code coverage report (JaCoCo)
./mvnw jacoco:report
open target/site/jacoco/index.html
```

### Load Testing

```bash
# Run all benchmarks (JMH + Gatling + Apache Bench)
./infrastructure/scripts/run-benchmarks.sh all

# View results
ls -la benchmark-results/
```

### Performance Targets

| Metric | Target | Actual |
|--------|--------|--------|
| Throughput | > 1000 TPS | ~850 TPS |
| p95 Latency | < 100ms | ~25ms |
| Error Rate | < 0.1% | < 0.01% |
| Availability | > 99.9% | 100% |

## 🏗️ Project Structure

```
oltp-demos/
├── src/
│   ├── main/
│   │   ├── java/com/oltp/demo/
│   │   │   ├── controller/        # REST API endpoints
│   │   │   ├── service/           # Business logic (demos)
│   │   │   ├── repository/        # Data access (JPA, jOOQ)
│   │   │   ├── domain/            # JPA entities
│   │   │   └── config/            # Spring configuration
│   │   └── resources/
│   │       ├── application.yml    # App configuration
│   │       ├── db/migration/      # Flyway migrations
│   │       └── logback-spring.xml # Logging config
│   └── test/                      # Tests
├── infrastructure/
│   ├── docker/                    # Docker Compose setup
│   │   ├── docker-compose.yml
│   │   ├── postgres/              # PostgreSQL config
│   │   ├── prometheus/            # Prometheus config
│   │   └── grafana/               # Grafana dashboards
│   └── scripts/                   # Automation scripts
│       ├── setup.sh               # One-command setup
│       ├── seed-data.sh           # Generate test data
│       ├── run-benchmarks.sh      # Run load tests
│       └── clean-reset.sh         # Reset database
├── docs/
│   ├── demonstrations/            # Demo guides (curl examples)
│   ├── architecture/              # Architecture docs, ADRs, diagrams
│   └── runbooks/                  # Operational guides
└── specs/                         # Specifications (spec-driven dev)
```

## 🛠️ Technology Stack

| Category | Technology | Purpose |
|----------|------------|---------|
| **Language** | Java 17+ | Modern Java features (records, pattern matching) |
| **Framework** | Spring Boot 3.2+ | Application framework, dependency injection |
| **Database** | PostgreSQL 15+ | ACID-compliant relational database |
| **ORM** | Spring Data JPA | Declarative data access |
| **SQL** | jOOQ | Type-safe SQL for advanced queries |
| **Migrations** | Flyway | Version-controlled database schema |
| **Connection Pool** | HikariCP | High-performance connection pooling |
| **Caching** | Redis | Distributed caching layer |
| **Metrics** | Micrometer + Prometheus | Metrics collection and storage |
| **Dashboards** | Grafana | Metrics visualization |
| **Tracing** | OpenTelemetry + Jaeger | Distributed request tracing |
| **Testing** | JUnit 5, TestContainers | Unit and integration testing |
| **Load Testing** | Gatling, JMH, Apache Bench | Performance benchmarking |
| **Resilience** | Resilience4j, Spring Retry | Circuit breakers, retries |

## 🎓 Learning Resources

### ACID Demonstrations

- [Atomicity](docs/demonstrations/acid/atomicity.md) - All-or-nothing execution
- [Consistency](docs/demonstrations/acid/consistency.md) - Constraint enforcement
- [Isolation](docs/demonstrations/acid/isolation.md) - Concurrent transactions
- [Durability](docs/demonstrations/acid/durability.md) - Crash recovery

### Concurrency Demonstrations

- [Optimistic Locking](docs/demonstrations/concurrency/optimistic-locking.md) - Version-based concurrency
- [Pessimistic Locking](docs/demonstrations/concurrency/pessimistic-locking.md) - Lock-based concurrency
- [Deadlocks](docs/demonstrations/concurrency/deadlocks.md) - Detection and prevention

### Failure Demonstrations

- [Failure Handling](docs/demonstrations/failure/failure-handling.md) - Retry, circuit breakers
- [Crash Recovery](docs/demonstrations/failure/recovery.md) - WAL and durability

## 🤝 Contributing

This project follows **spec-driven development**:

1. **Specifications first**: Write specs before implementation
2. **Tests before code**: TDD approach
3. **Constitution-driven**: See [.specify/memory/constitution.md](.specify/memory/constitution.md)

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Built with spec-driven development methodology
- Powered by Spring Boot and PostgreSQL
- Observability stack: Prometheus, Grafana, Jaeger
- Inspired by real-world production OLTP systems

## 📞 Support

- **Documentation**: See [docs/](docs/) directory
- **Issues**: [GitHub Issues](https://github.com/your-org/oltp-demos/issues)
- **Discussions**: [GitHub Discussions](https://github.com/your-org/oltp-demos/discussions)

---

**Built with ❤️ for learning OLTP core capabilities**
