# OLTP Core Capabilities Tech Demo

A production-grade demonstration of Online Transaction Processing (OLTP) patterns and best practices using Java 21 and Spring Boot 3.2+.

## Overview

This project showcases essential OLTP system capabilities through practical demonstrations:

- **ACID Transaction Guarantees** - Atomicity, Consistency, Isolation, Durability
- **Concurrency Control** - Optimistic/Pessimistic locking, deadlock handling
- **Performance Optimization** - Connection pooling, batching, caching, indexing
- **Comprehensive Observability** - Metrics, logging, distributed tracing
- **Failure Handling** - Retry logic, circuit breakers, crash recovery

## Project Philosophy

This project is built on **spec-driven development** principles, where:
1. Specifications are written before implementation
2. Tests are written before code
3. Performance is measured, not guessed
4. Observability is built-in, not bolted-on

See [.specify/memory/constitution.md](.specify/memory/constitution.md) for the complete set of governing principles and development guidelines.

## Technology Stack

### Core
- **Java 21 LTS** - Virtual Threads (Project Loom)
- **Spring Boot 3.2+** - Application framework
- **PostgreSQL 15+** - ACID-compliant database
- **HikariCP** - High-performance connection pooling

### Data Access
- **Spring Data JPA** - ORM with Hibernate
- **jOOQ** - Type-safe SQL queries
- **Flyway** - Database migrations

### Observability
- **Micrometer** - Metrics collection
- **Prometheus** - Metrics storage
- **Grafana** - Metrics visualization
- **OpenTelemetry** - Distributed tracing
- **Jaeger** - Trace visualization

### Resilience
- **Resilience4j** - Circuit breaker, retry, bulkhead
- **Spring Retry** - Declarative retry logic

### Testing
- **JUnit 5** - Unit testing
- **Testcontainers** - Integration testing with Docker
- **JMH** - Microbenchmarking
- **ArchUnit** - Architecture validation
- **Gatling** - JVM-based load testing
- **Locust** - Python-based load testing

## Performance Targets

Based on constitution.md guidelines:

- **Point queries**: < 5ms (p95)
- **Simple transactions**: < 10ms (p95)
- **Complex transactions**: < 50ms (p95)
- **Connection acquisition**: < 1ms (p95)
- **Throughput**: 1000+ TPS on standard hardware

## Quick Start

### Prerequisites

- Java 21 or higher
- Docker & Docker Compose
- Maven 3.9+ (or use included wrapper)

### Running the Demo

1. **Start Infrastructure**
```bash
docker-compose up -d
```

2. **Run Database Migrations**
```bash
./mvnw flyway:migrate
```

3. **Start Application**
```bash
./mvnw spring-boot:run
```

4. **Access Endpoints**
- API: http://localhost:8080/api
- Health: http://localhost:8080/actuator/health
- Metrics: http://localhost:8080/actuator/prometheus
- Grafana: http://localhost:3000
- Jaeger: http://localhost:16686

## Demonstrations

### ACID Transactions

**Atomicity** - All-or-nothing money transfers:
```bash
curl -X POST http://localhost:8080/api/demos/acid/atomicity/transfer \
  -H "Content-Type: application/json" \
  -d '{"fromAccountId": 1, "toAccountId": 2, "amount": 100.00}'
```

**Consistency** - Business rule enforcement:
```bash
curl -X POST http://localhost:8080/api/demos/acid/consistency/enforce-constraints \
  -H "Content-Type: application/json" \
  -d '{"accountId": 1, "withdrawalAmount": 1000.00}'
```

**Isolation** - Concurrent transaction handling:
```bash
curl -X POST http://localhost:8080/api/demos/acid/isolation/concurrent-transfers \
  -H "Content-Type: application/json" \
  -d '{"accountId": 1, "concurrentOperations": 10}'
```

**Durability** - Crash recovery verification:
```bash
curl http://localhost:8080/api/demos/acid/durability/crash-recovery
```

### Concurrency Control

**Optimistic Locking**:
```bash
curl -X POST http://localhost:8080/api/demos/concurrency/optimistic-locking \
  -H "Content-Type: application/json" \
  -d '{"accountId": 1, "operations": 5}'
```

**Pessimistic Locking**:
```bash
curl -X POST http://localhost:8080/api/demos/concurrency/pessimistic-locking \
  -H "Content-Type: application/json" \
  -d '{"accountId": 1, "operations": 5}'
```

### Performance Optimization

**Connection Pooling**:
```bash
curl http://localhost:8080/api/demos/performance/connection-pooling?requests=100
```

**Batch Operations**:
```bash
curl -X POST http://localhost:8080/api/demos/performance/batch-operations \
  -H "Content-Type: application/json" \
  -d '{"batchSize": 1000}'
```

**Caching**:
```bash
curl http://localhost:8080/api/demos/performance/caching?userId=1
```

## Project Structure

```
oltp-demo/
├── src/main/java/com/oltp/demo/
│   ├── config/          # Spring configuration
│   ├── domain/          # JPA entities
│   ├── repository/      # Data access
│   ├── service/         # Business logic
│   │   ├── acid/        # ACID demonstrations
│   │   ├── concurrency/ # Concurrency control
│   │   ├── performance/ # Performance optimizations
│   │   └── failure/     # Failure handling
│   ├── controller/      # REST endpoints
│   └── util/            # Utilities
├── src/main/resources/
│   ├── db/migration/    # Flyway SQL scripts
│   └── application.yml  # Configuration
├── src/test/
│   ├── integration/     # Integration tests
│   ├── performance/     # JMH benchmarks
│   └── architecture/    # ArchUnit tests
├── loadtest/
│   ├── gatling/         # Gatling scenarios
│   └── locust/          # Locust scenarios
├── infrastructure/
│   ├── docker/          # Docker configurations
│   └── scripts/         # Automation scripts
├── docs/                # Documentation
└── specs/               # Feature specifications
```

## Running Tests

**Unit Tests**:
```bash
./mvnw test
```

**Integration Tests**:
```bash
./mvnw verify -P integration-tests
```

**Performance Benchmarks**:
```bash
./mvnw verify -P benchmarks
```

**Load Tests** (Gatling):
```bash
./mvnw gatling:test
```

**Load Tests** (Locust):
```bash
cd loadtest/locust
locust -f scenarios/transfer_load.py
```

## Monitoring

### Metrics
Access Prometheus metrics at `/actuator/prometheus`:
- JVM metrics (heap, GC, threads)
- HikariCP pool metrics
- Transaction metrics
- Custom business metrics

### Distributed Tracing
View traces in Jaeger UI (http://localhost:16686):
- Request flows across services
- Database query performance
- Transaction boundaries
- Error traces

### Health Checks
```bash
curl http://localhost:8080/actuator/health
```

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines and best practices.

### Spec-Driven Development Workflow

This project uses spec-driven development:
1. `/speckit.specify` - Create feature specification
2. `/speckit.plan` - Create implementation plan
3. `/speckit.tasks` - Generate actionable tasks
4. `/speckit.implement` - Execute implementation
5. `/speckit.clarify` - Clarify underspecified areas
6. `/speckit.analyze` - Analyze consistency and quality

## Documentation

- **Architecture**: [docs/architecture/](docs/architecture/)
- **Demonstrations**: [docs/demonstrations/](docs/demonstrations/)
- **Runbooks**: [docs/runbooks/](docs/runbooks/)
- **API Specification**: [specs/001-oltp-core-demo/contracts/openapi.yaml](specs/001-oltp-core-demo/contracts/openapi.yaml)
- **Constitution**: [.specify/memory/constitution.md](.specify/memory/constitution.md)

## Key Concepts Demonstrated

### Transaction Management
- ACID compliance with Spring @Transactional
- Multiple isolation level demonstrations
- Deadlock detection and retry logic
- Savepoints and nested transactions

### Performance Optimization
- HikariCP connection pooling
- JPA query optimization with indexes
- Batch operations and bulk inserts
- Redis caching with Spring Cache

### Concurrency Handling
- Optimistic locking with @Version
- Pessimistic locking with LockModeType
- Concurrent transaction testing
- Race condition prevention patterns

### Observability
- Structured logging with correlation IDs
- Micrometer metrics with Prometheus
- OpenTelemetry distributed tracing
- HikariCP pool monitoring

## Quality Gates

Before merging:
- [ ] All tests pass (unit, integration, concurrency)
- [ ] Performance benchmarks meet targets
- [ ] Code coverage > 80%
- [ ] Database migrations are reversible
- [ ] No security vulnerabilities
- [ ] Documentation updated
- [ ] Observability instrumented

## License

MIT License - see LICENSE file for details

## References

- [ACID Properties](https://en.wikipedia.org/wiki/ACID)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)
- [Spring Boot Reference](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [HikariCP Performance Tips](https://github.com/brettwooldridge/HikariCP/wiki)
- [Designing Data-Intensive Applications](https://dataintensive.net/) by Martin Kleppmann

## Support

For issues and questions, see the project documentation or create an issue in the repository.

---

**Built with rigor. Tested with realism. Deployed with confidence.**
