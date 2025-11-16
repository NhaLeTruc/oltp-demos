# ADR 002: PostgreSQL over MySQL for OLTP Demonstrations

## Status

**Accepted** - 2025-11-16

## Context

We need a relational database that can clearly demonstrate OLTP core capabilities:

1. **ACID guarantees**: Full support for all isolation levels
2. **Concurrency control**: Clear examples of locking, deadlocks, and isolation phenomena
3. **Advanced features**: Indexes, constraints, triggers, stored procedures
4. **Observability**: Rich monitoring and diagnostic capabilities
5. **Performance**: Capable of handling high-concurrency workloads

## Decision

We will use **PostgreSQL 15+** as the primary database for all demonstrations.

## Rationale

### PostgreSQL Strengths

**1. Superior Isolation Level Support**:
- Supports all 4 ANSI SQL isolation levels: READ UNCOMMITTED, READ COMMITTED, REPEATABLE READ, SERIALIZABLE
- **Serializable Snapshot Isolation (SSI)**: True serializability without blocking
- Clear demonstration of isolation phenomena (dirty reads, phantom reads, etc.)

**MySQL Limitations**:
- REPEATABLE READ with gap locking (confusing behavior)
- READ UNCOMMITTED effectively behaves like READ COMMITTED
- SERIALIZABLE uses locking instead of MVCC (worse performance)

**2. Explicit Locking Mechanisms**:
```sql
-- PostgreSQL: Clear, explicit syntax
SELECT * FROM accounts WHERE id = 1 FOR UPDATE;  -- Exclusive lock
SELECT * FROM accounts WHERE id = 1 FOR SHARE;   -- Shared lock

-- MySQL: Less clear, mixed with isolation levels
SELECT * FROM accounts WHERE id = 1 FOR UPDATE;  -- Works
SELECT * FROM accounts WHERE id = 1 LOCK IN SHARE MODE;  -- Deprecated
```

**3. Advanced Constraint Support**:
- **CHECK constraints**: Any boolean expression
- **Exclusion constraints**: Range overlaps, temporal constraints
- **Deferrable constraints**: Control when constraints are checked
- **Partial indexes**: Conditional indexes

MySQL has limited CHECK constraint support (MySQL 8.0.16+), no exclusion or deferrable constraints.

**4. MVCC Implementation**:
- PostgreSQL MVCC is cleaner and easier to explain
- No UNDO log complexity (MySQL InnoDB)
- Easier to demonstrate snapshot isolation

**5. Rich Monitoring**:
- `pg_stat_activity`: See all active queries and lock waits
- `pg_locks`: Detailed lock information
- `pg_stat_statements`: Query performance tracking
- `EXPLAIN ANALYZE`: Superior query plan analysis

**6. Write-Ahead Logging (WAL)**:
- Clear WAL mechanism for durability demonstrations
- Easy to configure (`wal_level`, `synchronous_commit`, `fsync`)
- Point-in-time recovery capabilities

**7. PostgreSQL-Specific Features for Demos**:
- **Advisory locks**: Application-level locking
- **Listen/Notify**: Event-driven demonstrations
- **Window functions**: Advanced analytics
- **JSON support**: Modern data types

### PostgreSQL Cons

- **Vacuum overhead**: Requires maintenance (good teaching point!)
- **Write performance**: Generally slower than MySQL for write-heavy workloads
- **Connection pooling**: Requires external pooler (HikariCP, PgBouncer)

## Consequences

### Positive

1. **Clear ACID demonstrations**: All isolation levels work as documented
2. **Concurrency examples**: Easy to demonstrate deadlocks, lock timeouts, serialization failures
3. **Rich diagnostics**: `pg_stat_*` views provide excellent observability
4. **Production-realistic**: PostgreSQL is widely used in enterprise OLTP systems
5. **Educational value**: Clean implementation makes concepts easier to understand

### Negative

1. **Learning curve**: Slightly steeper than MySQL for beginners
2. **Vacuum management**: Must explain autovacuum in documentation
3. **Connection limits**: Default `max_connections = 100` requires connection pooling

### Mitigations

- **Documentation**: Comprehensive guides for PostgreSQL-specific concepts
- **Auto-configuration**: Docker Compose handles PostgreSQL tuning
- **Connection pooling**: HikariCP manages connection lifecycle

## Comparison Matrix

| Feature | PostgreSQL | MySQL | Winner |
|---------|-----------|-------|--------|
| **ACID compliance** | Full support for all isolation levels | Limited SERIALIZABLE, confusing REPEATABLE READ | PostgreSQL |
| **Concurrency** | MVCC, SSI, no deadlocks in SERIALIZABLE | Locks, gap locks, complex deadlock scenarios | PostgreSQL |
| **Constraints** | CHECK, exclusion, deferrable | Limited CHECK, no exclusion/deferrable | PostgreSQL |
| **Monitoring** | pg_stat_*, pg_locks, rich diagnostics | performance_schema, less intuitive | PostgreSQL |
| **Write performance** | Good | Excellent | MySQL |
| **Read performance** | Excellent | Good | PostgreSQL |
| **JSON support** | JSONB (binary, indexed) | JSON (text-based) | PostgreSQL |
| **Window functions** | Full support | Full support (8.0+) | Tie |
| **Learning curve** | Moderate | Easy | MySQL |
| **Enterprise adoption** | High | Very high | Tie |

## Alternatives Considered

| Database | Pros | Cons | Verdict |
|----------|------|------|---------|
| **MySQL 8.0** | Widely known, fast writes | Isolation levels confusing, limited constraints | Rejected - Poor for isolation demos |
| **SQLite** | Simple, embedded | No true concurrency, limited features | Rejected - Not suitable for OLTP demos |
| **Oracle Database** | Enterprise-grade, feature-rich | Proprietary, expensive, complex setup | Rejected - Accessibility issues |
| **Microsoft SQL Server** | Excellent ACID support | Windows-centric, licensing complexity | Rejected - Platform limitations |
| **CockroachDB** | Distributed, PostgreSQL-compatible | Complex for demos, different isolation model | Rejected - Adds unnecessary complexity |

## Configuration Decisions

### Key PostgreSQL Settings for Demos

```properties
# ACID Durability
wal_level = replica
fsync = on
synchronous_commit = on

# Concurrency
max_connections = 200
shared_buffers = 256MB

# Monitoring
track_activities = on
track_counts = on
log_lock_waits = on

# Performance
effective_cache_size = 1GB
work_mem = 16MB
```

## Use Cases Where MySQL Would Be Better

- **Write-heavy workloads**: MySQL InnoDB has better write throughput
- **Simple CRUD operations**: MySQL is easier for basic operations
- **Wide adoption**: More developers familiar with MySQL

**Why these don't apply to our demos**:
- We're demonstrating ACID properties, not optimizing writes
- We need complex transactions, not simple CRUD
- Educational value more important than familiarity

## References

- [PostgreSQL Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)
- [MySQL InnoDB Locking](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking.html)
- [A Critique of ANSI SQL Isolation Levels](https://www.microsoft.com/en-us/research/publication/a-critique-of-ansi-sql-isolation-levels/)
- [PostgreSQL MVCC](https://www.postgresql.org/docs/current/mvcc.html)

## Review

- **Decision date**: 2025-11-16
- **Participants**: Technical team
- **Next review**: After database performance benchmarks (Q1 2026)
