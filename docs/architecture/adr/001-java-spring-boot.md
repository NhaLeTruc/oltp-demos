# ADR 001: Java and Spring Boot for OLTP Demo

## Status

**Accepted** - 2025-11-16

## Context

We need to choose a technology stack for demonstrating OLTP (Online Transaction Processing) core capabilities including ACID transactions, concurrency control, performance optimization, and observability. The demonstration must be:

1. **Accessible**: Easy for developers to understand and run
2. **Production-realistic**: Using patterns and tools from real-world systems
3. **Well-documented**: Extensive ecosystem and community support
4. **Demonstrative**: Clear examples of OLTP concepts

## Decision

We will use **Java 17+** with **Spring Boot 3.2+** as the primary technology stack.

## Rationale

### Language Choice: Java

**Pros**:
- **Industry standard**: Widely used for enterprise OLTP systems
- **Strong typing**: Catches errors at compile-time, essential for financial demos
- **JVM ecosystem**: Mature tools for profiling, monitoring, and optimization
- **Thread safety**: Built-in concurrency primitives (`synchronized`, `volatile`, `Atomic*`)
- **Performance**: JIT compilation provides excellent throughput for OLTP workloads
- **Educational**: Most developers have Java experience

**Cons**:
- Verbosity compared to modern languages
- Longer startup time than lightweight alternatives
- Memory footprint higher than compiled languages (C++, Go)

**Why not alternatives**:
- **Python**: Global Interpreter Lock (GIL) limits concurrency demonstrations
- **Node.js**: Single-threaded event loop not suitable for multi-threaded OLTP demos
- **Go**: Less mature ORM/transaction libraries, smaller ecosystem
- **C++**: Steeper learning curve, more complex setup

### Framework Choice: Spring Boot

**Pros**:
- **Spring Data JPA**: Excellent ORM with declarative transaction management
- **Spring Actuator**: Built-in health checks, metrics, and management endpoints
- **Auto-configuration**: Minimal boilerplate for database connections, caching, monitoring
- **Ecosystem integration**: Seamless integration with PostgreSQL, Redis, Prometheus, Jaeger
- **Testing support**: Comprehensive testing utilities (TestContainers, MockMvc)
- **Transaction management**: `@Transactional` makes ACID demonstrations clear and simple
- **Dependency injection**: Clean separation of concerns for demo services

**Cons**:
- "Magic" auto-configuration can obscure low-level details
- Higher memory usage than minimal frameworks
- Startup time longer than lightweight frameworks (Micronaut, Quarkus)

**Why not alternatives**:
- **Jakarta EE (formerly Java EE)**: More verbose, less modern tooling
- **Micronaut**: Smaller ecosystem, less documentation
- **Quarkus**: Native compilation complicates JMH benchmarking
- **Plain Servlets/JDBC**: Too much boilerplate, obscures OLTP concepts

## Consequences

### Positive

1. **Developer familiarity**: Most developers can understand and modify the code
2. **Comprehensive examples**: Spring's annotations make transaction boundaries explicit:
   ```java
   @Transactional(isolation = Isolation.SERIALIZABLE)
   public void demonstrateSerializability() {
       // Clear transaction scope
   }
   ```
3. **Rich ecosystem**: Easy to integrate PostgreSQL, Flyway, HikariCP, Micrometer, etc.
4. **Production patterns**: Code resembles real-world enterprise applications
5. **Testing capabilities**: TestContainers enables realistic integration tests

### Negative

1. **Startup overhead**: ~10-15 second startup time (acceptable for demos)
2. **Memory footprint**: ~300MB minimum heap (not a concern for demonstrations)
3. **Learning curve**: Developers must understand Spring annotations and auto-configuration

### Mitigations

- **Documentation**: Explain Spring Boot "magic" in runbooks
- **Performance**: Use Spring Boot DevTools for fast restarts during development
- **Simplicity**: Keep configuration minimal, avoid over-engineering

## Alternatives Considered

| Alternative | Pros | Cons | Verdict |
|-------------|------|------|---------|
| **Python + FastAPI** | Simple syntax, fast to prototype | GIL limits concurrency, weak typing | Rejected - Not suitable for concurrency demos |
| **Node.js + Express** | Lightweight, familiar to many | Single-threaded, async model != OLTP | Rejected - Wrong concurrency model |
| **Go + Gin** | Fast, simple concurrency | Less mature ORM, smaller ecosystem | Rejected - Harder to demonstrate JPA patterns |
| **C# + ASP.NET Core** | Excellent performance, modern | Windows-centric, smaller ecosystem | Considered but Java more universal |
| **Kotlin + Spring Boot** | Concise syntax, null safety | Smaller community, less familiar | Considered but Java more accessible |

## References

- [Spring Boot Documentation](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [Spring Data JPA](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/)
- [Spring Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html)
- [Java Concurrency in Practice](https://jcip.net/)

## Review

- **Decision date**: 2025-11-16
- **Participants**: Technical team
- **Next review**: After first user feedback (Q1 2026)
