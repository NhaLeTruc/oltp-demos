# OLTP Core Capabilities Tech Demo Constitution

## Core Principles

### I. Data Integrity First (NON-NEGOTIABLE)
**ACID compliance is the foundation of OLTP systems. Never compromise transactional integrity for performance.**

- **Atomicity**: All transactions must complete fully or roll back completely
- **Consistency**: Database must transition from one valid state to another
- **Isolation**: Concurrent transactions must not interfere with each other
- **Durability**: Committed transactions must survive system failures

**Implementation Requirements**:
- Use proper transaction boundaries with explicit BEGIN/COMMIT/ROLLBACK
- Implement optimistic or pessimistic locking strategies based on contention patterns
- Test all failure scenarios: network failures, crashes mid-transaction, concurrent updates
- Document isolation levels used and justify deviations from SERIALIZABLE

### II. Performance Through Design, Not Hacks
**Optimize at the architecture level. Measure before optimizing. Never sacrifice correctness for speed.**

**Latency Targets**:
- Point queries: < 5ms (p95)
- Simple transactions: < 10ms (p95)
- Complex transactions: < 50ms (p95)
- Connection acquisition: < 1ms (p95)

**Performance Principles**:
- Index strategy must be documented and justified
- No N+1 queries - use JOINs, batch operations, or data denormalization
- Connection pooling is mandatory (never create connections per request)
- Query plans must be analyzed for all critical paths
- Hot paths must be benchmarked under realistic load

**Measurement Requirements**:
- Every optimization must be preceded by profiling data
- Benchmarks must include: throughput (TPS), latency (p50/p95/p99), resource utilization
- Load testing with realistic data volumes and concurrency levels

### III. Concurrency & Scalability
**Design for concurrent access from day one. Scale horizontally where possible, vertically when necessary.**

**Concurrency Strategy**:
- Minimize lock contention through proper transaction design
- Use row-level locking over table-level locking
- Implement retry logic with exponential backoff for deadlocks
- Document hotspots and contention points

**Scalability Approach**:
- Read replicas for read-heavy workloads
- Partitioning/sharding strategy for horizontal scaling
- Caching layer (Redis/Memcached) for hot data with invalidation strategy
- Connection pooling sized appropriately (formula: connections = ((core_count * 2) + effective_spindle_count))

**Testing Requirements**:
- Concurrent transaction tests with at least 100 parallel clients
- Deadlock detection and resolution testing
- Race condition testing using tools like jepsen or similar

### IV. Observability & Debugging (NON-NEGOTIABLE)
**You cannot fix what you cannot see. Instrument everything.**

**Logging Requirements**:
- Structured logging (JSON format) with consistent schema
- Log levels: DEBUG, INFO, WARN, ERROR with appropriate usage
- Every transaction must have a correlation ID
- Log all slow queries (> 50ms) with full context
- Include timing information: transaction start, duration, query execution times

**Metrics Requirements**:
- Transaction throughput (TPS) by operation type
- Query latency histograms (p50, p95, p99, p999)
- Connection pool metrics: active, idle, waiting
- Database-level metrics: cache hit ratio, index usage, lock waits
- Error rates and types

**Tracing Requirements**:
- Distributed tracing for multi-service transactions
- Query execution tracing with explain plans
- Lock acquisition and wait time tracing

**Monitoring Tools**:
- Expose Prometheus metrics endpoint
- Grafana dashboards for real-time monitoring
- Database-specific tools (pg_stat_statements, slow query log, etc.)

### V. Test-First Development (NON-NEGOTIABLE)
**Tests are the specification. Write tests before implementation.**

**Testing Strategy**:
1. **Unit Tests**: Test business logic in isolation
2. **Integration Tests**: Test database interactions with real DB (use containers)
3. **Transaction Tests**: Verify ACID properties under failure scenarios
4. **Concurrency Tests**: Test race conditions, deadlocks, isolation
5. **Performance Tests**: Benchmark critical paths under load
6. **Chaos Tests**: Test behavior under failures (network, disk, process crashes)

**Coverage Requirements**:
- Unit test coverage: > 80%
- Integration test coverage for all database operations
- Every bug fix must include a regression test
- Performance tests for all critical paths (< 50ms target)

**Test Data**:
- Realistic data volumes (millions of rows, not hundreds)
- Data distribution matching production patterns
- Test with dirty data (nulls, edge cases, unicode, SQL injection attempts)

### VI. Security & Data Protection
**Protect data at rest and in transit. Assume breach.**

**Authentication & Authorization**:
- Principle of least privilege for database users
- Separate credentials for read-only vs read-write operations
- No credentials in code - use environment variables or secret managers
- Rotate credentials regularly (90-day maximum)

**Input Validation**:
- Parameterized queries only (NEVER string concatenation for SQL)
- Validate and sanitize all inputs at API boundary
- Type safety enforced at compile time where possible

**Data Protection**:
- Encryption at rest for sensitive data (PII, financial data)
- TLS/SSL for all database connections
- Audit logging for data access and modifications
- Implement data retention and purging policies

### VII. Simplicity & Pragmatism
**Prefer boring technology. Optimize for maintainability. YAGNI.**

**Technology Choices**:
- Use proven, mature technologies over bleeding-edge
- Minimize dependencies - each dependency is a liability
- Standard patterns over clever solutions
- Comprehensive documentation for non-standard approaches

**Design Principles**:
- Start with a monolith, split only when necessary
- Denormalization must be justified with performance data
- No premature optimization - measure first
- Delete code aggressively - less code = fewer bugs

**Code Quality**:
- Clear, self-documenting code over comments
- Consistent naming conventions
- Small, focused functions (< 50 lines)
- Avoid deep nesting (max 3 levels)

## Technology Stack Standards

### Database Layer
**Primary Database**: PostgreSQL 15+ or MySQL 8.0+
- Strong ACID compliance
- Mature replication and backup solutions
- Excellent performance and tooling

**Alternatives Considered**:
- **CockroachDB**: For distributed, multi-region requirements
- **SingleStore**: For hybrid OLTP/OLAP workloads
- **Redis**: For caching layer only, NOT primary storage

**Connection Management**:
- Connection pooler: PgBouncer (PostgreSQL) or ProxySQL (MySQL)
- Application-level pooling: HikariCP (Java), asyncpg (Python), node-postgres (Node.js)

### Backend Framework
**Preferred Stack** (choose based on team expertise):
- **Java**: Spring Boot + Hibernate/jOOQ + HikariCP
- **Python**: FastAPI/Django + SQLAlchemy + asyncpg
- **Go**: Gin/Echo + sqlx + pgx
- **Node.js**: Express/Fastify + Sequelize/Prisma + pg

**Requirements**:
- Must support connection pooling
- Must support prepared statements
- Must have excellent ORM or query builder
- Must support async/concurrent operations

### Observability Stack
- **Metrics**: Prometheus + Grafana
- **Logging**: Structured JSON logs + ELK Stack or Loki
- **Tracing**: OpenTelemetry + Jaeger or Zipkin
- **APM**: Database-specific tools (pg_stat_statements, MySQL Performance Schema)

## Development Workflow

### Feature Development Process
1. **Specification**: Write detailed spec with requirements, edge cases, performance targets
2. **Design Review**: Architecture review focusing on ACID compliance, scalability, performance
3. **Test Writing**: Write tests covering happy path, edge cases, failure scenarios
4. **Implementation**: Code to make tests pass
5. **Benchmarking**: Run performance tests, validate against latency targets
6. **Code Review**: Review for correctness, security, performance, maintainability
7. **Integration Testing**: Test with realistic data and concurrency
8. **Documentation**: Update schema docs, API docs, runbooks

### Code Review Requirements
**Blocking Issues** (must fix):
- ACID violations or potential data corruption
- Security vulnerabilities (SQL injection, auth bypass, etc.)
- Missing error handling or transaction boundaries
- Missing or failing tests
- Performance regressions without justification

**Non-Blocking** (should fix):
- Code style inconsistencies
- Missing comments for complex logic
- Suboptimal but functional implementations

### Quality Gates
**Pre-Merge Checklist**:
- [ ] All tests pass (unit, integration, concurrency)
- [ ] Performance benchmarks meet targets
- [ ] Code coverage > 80%
- [ ] Database migrations are reversible
- [ ] No new security vulnerabilities (SAST scan)
- [ ] Documentation updated
- [ ] Observability instrumented (logs, metrics, traces)

## Database Schema Standards

### Schema Design Principles
- **Normalization**: Start with 3NF, denormalize only with performance justification
- **Primary Keys**: Use surrogate keys (auto-increment INT/BIGINT or UUID)
- **Foreign Keys**: Always define FK constraints for referential integrity
- **Indexes**: Create indexes for: PKs, FKs, frequently queried columns, sort columns
- **Constraints**: Use CHECK constraints, NOT NULL, UNIQUE where appropriate
- **Types**: Use appropriate types (don't store dates as strings)

### Migration Strategy
- **Tool**: Flyway, Liquibase, or Alembic
- **Reversibility**: Every migration must have a rollback script
- **Testing**: Test migrations on realistic data volumes
- **Zero-Downtime**: Design migrations for zero-downtime deployment (expand-migrate-contract pattern)

### Naming Conventions
- **Tables**: plural nouns, snake_case (`users`, `order_items`)
- **Columns**: snake_case (`created_at`, `user_id`)
- **Indexes**: `idx_table_column` (`idx_users_email`)
- **Foreign Keys**: `fk_child_parent` (`fk_orders_users`)
- **Constraints**: `chk_table_constraint` (`chk_users_email_format`)

## Performance Baselines

### Benchmark Requirements
**Test Environment**:
- Database: Same version as production
- Data Volume: Minimum 1M rows in main tables
- Concurrency: 100 concurrent clients
- Duration: Minimum 5-minute steady state

**Metrics to Capture**:
- Throughput: Transactions per second (TPS)
- Latency: p50, p95, p99, p999 response times
- Resource Utilization: CPU, memory, disk I/O, network
- Database Metrics: Cache hit ratio, index usage, lock waits, connection pool stats

**Regression Testing**:
- Run benchmarks on every performance-related change
- Alert on > 10% regression in throughput or latency
- Investigate and document any significant changes

## Disaster Recovery & High Availability

### Backup Strategy
- **Frequency**: Continuous WAL archiving + daily full backups
- **Retention**: 30 days point-in-time recovery
- **Testing**: Quarterly restore drills
- **Offsite**: Backups stored in separate region/availability zone

### High Availability
- **Replication**: Synchronous replication for HA, asynchronous for read replicas
- **Failover**: Automated failover with health checks (max 30s downtime)
- **Monitoring**: Alert on replication lag > 5 seconds

## Governance

### Constitution Authority
This constitution **supersedes all other development practices**. All code reviews, architectural decisions, and implementation choices must comply with these principles.

### Amendment Process
1. Propose amendment with justification and impact analysis
2. Team review and discussion
3. Approval requires consensus (or 75% vote if consensus not reached)
4. Document migration plan for existing code
5. Update constitution with version bump and amendment date

### Compliance
- All PRs must include self-certification of constitution compliance
- Reviewers must verify compliance with core principles
- Non-compliance must be explicitly justified and approved
- Technical debt must be tracked and prioritized

### Exceptions
Exceptions to the constitution require:
1. Written justification with risk assessment
2. Approval from tech lead or architect
3. Documentation in ADR (Architecture Decision Record)
4. Remediation plan if temporary exception
5. Security review for security-related exceptions

**Version**: 1.0.0 | **Ratified**: 2025-11-16 | **Last Amended**: 2025-11-16
