# OLTP Core Capabilities Tech Demo

A demonstration project showcasing best practices and core capabilities for building production-grade **Online Transaction Processing (OLTP)** systems.

## Overview

This project demonstrates essential OLTP system capabilities including:

- **ACID-compliant transactions** with proper isolation and consistency guarantees
- **High-performance data access** with connection pooling and query optimization
- **Concurrent transaction handling** with deadlock detection and retry logic
- **Horizontal and vertical scalability** patterns
- **Comprehensive observability** with metrics, logging, and tracing
- **Security best practices** for data protection and access control

## Project Philosophy

This project is built on **spec-driven development** principles, where:
1. Specifications are written before implementation
2. Tests are written before code
3. Performance is measured, not guessed
4. Observability is built-in, not bolted-on

See [.specify/memory/constitution.md](.specify/memory/constitution.md) for the complete set of governing principles and development guidelines.

## Core Principles (Non-Negotiable)

### I. Data Integrity First
ACID compliance is the foundation. Never compromise transactional integrity for performance.

### II. Performance Through Design
Optimize at the architecture level. Measure before optimizing. Never sacrifice correctness for speed.

### III. Concurrency & Scalability
Design for concurrent access from day one. Scale horizontally where possible, vertically when necessary.

### IV. Observability & Debugging
You cannot fix what you cannot see. Instrument everything.

### V. Test-First Development
Tests are the specification. Write tests before implementation.

### VI. Security & Data Protection
Protect data at rest and in transit. Assume breach.

### VII. Simplicity & Pragmatism
Prefer boring technology. Optimize for maintainability. YAGNI.

## Technology Stack

### Database
- **Primary**: PostgreSQL 15+ or MySQL 8.0+
- **Caching**: Redis
- **Connection Pooling**: PgBouncer (PostgreSQL) / ProxySQL (MySQL)

### Backend
Choose based on team expertise:
- **Java**: Spring Boot + Hibernate/jOOQ + HikariCP
- **Python**: FastAPI/Django + SQLAlchemy + asyncpg
- **Go**: Gin/Echo + sqlx + pgx
- **Node.js**: Express/Fastify + Sequelize/Prisma + pg

### Observability
- **Metrics**: Prometheus + Grafana
- **Logging**: Structured JSON + ELK Stack or Loki
- **Tracing**: OpenTelemetry + Jaeger/Zipkin
- **APM**: Database-specific tools (pg_stat_statements, etc.)

## Performance Targets

- **Point queries**: < 5ms (p95)
- **Simple transactions**: < 10ms (p95)
- **Complex transactions**: < 50ms (p95)
- **Connection acquisition**: < 1ms (p95)
- **Throughput**: Measured in transactions per second (TPS)

## Getting Started

### Prerequisites
- Docker and Docker Compose (for database and infrastructure)
- Your chosen backend runtime (Java/Python/Go/Node.js)
- Git

### Development Workflow

1. **Read the Constitution**: Start with [.specify/memory/constitution.md](.specify/memory/constitution.md)
2. **Write Specification**: Document what you're building
3. **Write Tests**: Define expected behavior through tests
4. **Implement**: Make tests pass
5. **Benchmark**: Validate performance targets
6. **Review**: Ensure compliance with constitution
7. **Deploy**: Ship with confidence

## Project Structure

```
.
├── .specify/                  # Spec-driven development artifacts
│   ├── memory/
│   │   └── constitution.md   # Project governing principles
│   └── templates/            # Templates for specs, plans, tasks
├── .claude/                  # Claude Code configuration
│   └── commands/             # Custom slash commands
├── src/                      # Source code (to be created)
├── tests/                    # Test suites (to be created)
├── benchmarks/               # Performance benchmarks (to be created)
├── migrations/               # Database migrations (to be created)
└── README.md                 # This file
```

## Development Commands

### Spec-Driven Development
- `/speckit.specify` - Create feature specification
- `/speckit.plan` - Create implementation plan
- `/speckit.tasks` - Generate actionable tasks
- `/speckit.implement` - Execute implementation
- `/speckit.clarify` - Clarify underspecified areas
- `/speckit.analyze` - Analyze consistency and quality

## Key Concepts Demonstrated

### Transaction Management
- Explicit transaction boundaries
- Isolation level handling
- Deadlock detection and retry logic
- Savepoints and nested transactions

### Performance Optimization
- Connection pooling configuration
- Query optimization and index strategy
- Batch operations and bulk inserts
- Caching patterns with invalidation

### Concurrency Handling
- Optimistic vs pessimistic locking
- Row-level locking strategies
- Concurrent transaction testing
- Race condition prevention

### Observability
- Structured logging with correlation IDs
- Prometheus metrics exposition
- Distributed tracing
- Slow query logging and analysis

### Testing Strategy
- Unit tests for business logic
- Integration tests with real database
- Concurrency tests with parallel clients
- Chaos testing for failure scenarios
- Performance regression testing

## Contributing

All contributions must comply with the project constitution. Please:

1. Read [.specify/memory/constitution.md](.specify/memory/constitution.md)
2. Write specifications for new features
3. Write tests before implementation
4. Ensure performance benchmarks pass
5. Add observability instrumentation
6. Document architectural decisions

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

[To be determined]

## References

- [ACID Properties](https://en.wikipedia.org/wiki/ACID)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)
- [Database Performance Best Practices](https://use-the-index-luke.com/)
- [Designing Data-Intensive Applications](https://dataintensive.net/) by Martin Kleppmann

---

**Built with rigor. Tested with realism. Deployed with confidence.**
