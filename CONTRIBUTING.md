# Contributing to OLTP Core Capabilities Tech Demo

Thank you for your interest in contributing! This document provides guidelines and best practices for contributing to this project.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Workflow](#development-workflow)
- [Coding Standards](#coding-standards)
- [Testing Requirements](#testing-requirements)
- [Performance Guidelines](#performance-guidelines)
- [Documentation](#documentation)
- [Pull Request Process](#pull-request-process)

## Code of Conduct

This project adheres to the principles outlined in [.specify/memory/constitution.md](.specify/memory/constitution.md). All contributors must:

- Prioritize data integrity and ACID compliance
- Write tests before implementation
- Measure performance, don't guess
- Add observability instrumentation
- Follow security best practices
- Keep solutions simple and pragmatic

## Getting Started

### Prerequisites

1. **Install Development Tools**:
   - Java 21 JDK (OpenJDK or Oracle)
   - Docker Desktop or Docker Engine + Docker Compose
   - Maven 3.9+ (or use `./mvnw`)
   - Git
   - Your favorite IDE (IntelliJ IDEA recommended)

2. **Clone and Setup**:
   ```bash
   git clone <repository-url>
   cd oltp-demos
   ./mvnw clean install
   docker-compose up -d
   ./mvnw flyway:migrate
   ```

3. **Verify Setup**:
   ```bash
   ./mvnw test
   ./mvnw spring-boot:run
   curl http://localhost:8080/actuator/health
   ```

## Development Workflow

### Spec-Driven Development

This project uses **spec-driven development**. Follow this workflow:

1. **Specification First** (`/speckit.specify`)
   - Document WHAT you're building and WHY
   - Define user stories and acceptance criteria
   - Get alignment before writing code

2. **Implementation Planning** (`/speckit.plan`)
   - Choose technologies and architectural patterns
   - Design data models and API contracts
   - Document technical decisions in research.md

3. **Task Breakdown** (`/speckit.tasks`)
   - Generate actionable implementation tasks
   - Organize by dependencies and priority
   - Estimate complexity

4. **Test-First Implementation** (`/speckit.implement`)
   - Write tests before code
   - Implement to make tests pass
   - Benchmark performance
   - Add observability

5. **Review and Iterate**
   - Ensure constitutional compliance
   - Verify performance targets
   - Update documentation

### Branch Strategy

- `main` - Production-ready code
- `feature/<feature-name>` - Feature branches
- `fix/<issue-number>` - Bug fixes
- `perf/<optimization-name>` - Performance improvements

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Types**:
- `feat`: New feature
- `fix`: Bug fix
- `perf`: Performance improvement
- `refactor`: Code refactoring
- `test`: Test additions/changes
- `docs`: Documentation updates
- `chore`: Maintenance tasks

**Examples**:
```
feat(acid): implement atomicity demonstration with rollback scenarios

- Add TransferService with @Transactional support
- Create rollback scenario when account balance insufficient
- Add integration test with concurrent transfers
- Instrument with Micrometer metrics

Closes #42
```

```
perf(pooling): optimize HikariCP configuration for high throughput

- Increase maximumPoolSize from 10 to 20
- Set connectionTimeout to 250ms
- Enable prepStmtCacheSize for better performance
- Benchmark shows 40% TPS improvement (750 -> 1050 TPS)

Related to #38
```

## Coding Standards

### Java Code Style

Follow [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) with these specifics:

1. **Indentation**: 4 spaces (not tabs)
2. **Line Length**: 120 characters max
3. **Imports**: No wildcards, organize by groups
4. **Naming**:
   - Classes: `PascalCase`
   - Methods/Variables: `camelCase`
   - Constants: `UPPER_SNAKE_CASE`
   - Packages: `lowercase`

### Code Organization

```java
package com.oltp.demo.service.acid;

import java.util.List;                    // Java core
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;    // Third-party
import org.springframework.transaction.annotation.Transactional;

import com.oltp.demo.domain.Account;       // Project imports
import com.oltp.demo.repository.AccountRepository;

import lombok.RequiredArgsConstructor;     // Lombok
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating ACID atomicity property.
 *
 * Showcases all-or-nothing transaction behavior where
 * money transfers either complete fully or rollback completely.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AtomicityDemoService {

    private final AccountRepository accountRepository;
    private final MetricsHelper metricsHelper;

    /**
     * Transfers money between accounts with atomicity guarantee.
     *
     * @param fromAccountId source account ID
     * @param toAccountId destination account ID
     * @param amount transfer amount
     * @return transfer result with correlation ID
     * @throws InsufficientFundsException if balance insufficient
     */
    @Transactional
    public TransferResult transfer(Long fromAccountId, Long toAccountId, BigDecimal amount) {
        // Implementation
    }
}
```

### Documentation

1. **All public classes** must have Javadoc describing purpose
2. **All public methods** must document parameters, return values, exceptions
3. **Complex algorithms** should have inline comments explaining logic
4. **Magic numbers** must be extracted to named constants

## Testing Requirements

### Test Coverage Targets

- **Overall**: ≥ 80% line coverage
- **Service Layer**: ≥ 90% line coverage
- **Critical Paths**: 100% line coverage

### Test Types

#### 1. Unit Tests

Location: `src/test/java/com/oltp/demo/unit/`

```java
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private TransferService transferService;

    @Test
    void transfer_WithSufficientBalance_ShouldSucceed() {
        // Given
        Account from = createAccount(1L, new BigDecimal("100.00"));
        Account to = createAccount(2L, new BigDecimal("50.00"));

        when(accountRepository.findById(1L)).thenReturn(Optional.of(from));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(to));

        // When
        TransferResult result = transferService.transfer(1L, 2L, new BigDecimal("30.00"));

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(from.getBalance()).isEqualByComparingTo("70.00");
        assertThat(to.getBalance()).isEqualByComparingTo("80.00");
    }
}
```

#### 2. Integration Tests

Location: `src/test/java/com/oltp/demo/integration/`

Use **Testcontainers** for real database testing:

```java
@SpringBootTest
@Testcontainers
class AtomicityDemoIntegrationTest extends BaseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("testdb");

    @Autowired
    private AtomicityDemoService atomicityService;

    @Test
    @Transactional
    void transfer_WhenExceptionThrown_ShouldRollback() {
        // Test atomicity with rollback scenario
    }
}
```

#### 3. Concurrency Tests

Test race conditions and concurrent access:

```java
@Test
void concurrentTransfers_ShouldMaintainConsistency() throws Exception {
    int concurrentOperations = 10;
    ExecutorService executor = Executors.newFixedThreadPool(concurrentOperations);

    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    List<Future<TransferResult>> futures = new ArrayList<>();

    for (int i = 0; i < concurrentOperations; i++) {
        futures.add(executor.submit(() -> {
            latch.countDown();
            latch.await();
            return transferService.transfer(1L, 2L, new BigDecimal("10.00"));
        }));
    }

    // Verify results and data consistency
}
```

#### 4. Performance Benchmarks

Location: `src/test/java/com/oltp/demo/performance/`

Use **JMH** for microbenchmarks:

```java
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class TransferBenchmark {

    @Benchmark
    public void benchmarkSimpleTransfer(Blackhole blackhole) {
        TransferResult result = transferService.transfer(1L, 2L, new BigDecimal("10.00"));
        blackhole.consume(result);
    }
}
```

#### 5. Architecture Tests

Use **ArchUnit** to enforce architectural rules:

```java
@AnalyzeClasses(packages = "com.oltp.demo")
class ArchitectureTest {

    @ArchTest
    static final ArchRule servicesHaveServiceSuffix =
        classes().that().resideInAPackage("..service..")
            .should().haveSimpleNameEndingWith("Service");

    @ArchTest
    static final ArchRule servicesShouldBeAnnotated =
        classes().that().resideInAPackage("..service..")
            .should().beAnnotatedWith(Service.class);
}
```

### Running Tests

```bash
# Unit tests only
./mvnw test

# Integration tests
./mvnw verify -P integration-tests

# Performance benchmarks
./mvnw verify -P benchmarks

# All tests with coverage
./mvnw clean verify jacoco:report

# View coverage report
open target/site/jacoco/index.html
```

## Performance Guidelines

### Performance Targets (from constitution.md)

All code must meet these targets on standard hardware:

- **Point queries**: < 5ms (p95)
- **Simple transactions**: < 10ms (p95)
- **Complex transactions**: < 50ms (p95)
- **Connection acquisition**: < 1ms (p95)
- **Throughput**: ≥ 1000 TPS under load

### Performance Testing

1. **Write JMH benchmarks** for critical paths
2. **Run load tests** with Gatling/Locust before merging
3. **Monitor metrics** in Grafana during testing
4. **Document results** in PR description

### Optimization Best Practices

1. **Connection Pooling**:
   - Use HikariCP with tuned settings
   - Monitor pool exhaustion metrics
   - Set appropriate timeouts

2. **Query Optimization**:
   - Use proper indexes (see data-model.md)
   - Prefer batch operations over loops
   - Use jOOQ for complex queries

3. **Caching**:
   - Cache reference data (account types, etc.)
   - Use Redis for distributed caching
   - Implement cache invalidation

4. **JPA Best Practices**:
   - Avoid N+1 queries (use JOIN FETCH)
   - Use pagination for large result sets
   - Leverage second-level cache when appropriate

## Documentation

### Required Documentation

1. **Javadoc**: All public APIs
2. **README**: Usage examples for new features
3. **ADRs**: Architecture decisions in `docs/architecture/adr/`
4. **Runbooks**: Operational procedures in `docs/runbooks/`
5. **API Spec**: Update OpenAPI spec for new endpoints

### Architecture Decision Records (ADRs)

When making significant architectural decisions, document them:

```markdown
# ADR-XXX: Title

## Status
Accepted | Rejected | Deprecated | Superseded by ADR-YYY

## Context
What is the issue we're trying to solve?

## Decision
What decision did we make?

## Consequences
What are the positive and negative consequences?

## Alternatives Considered
What other options did we evaluate?
```

## Pull Request Process

### Before Creating PR

1. ✅ All tests pass locally
2. ✅ Performance benchmarks meet targets
3. ✅ Code coverage ≥ 80%
4. ✅ No compiler warnings
5. ✅ Database migrations are reversible
6. ✅ Documentation updated
7. ✅ Observability instrumented

### PR Template

```markdown
## Description
Brief description of changes

## Type of Change
- [ ] Bug fix
- [ ] New feature
- [ ] Performance improvement
- [ ] Documentation update

## Related Issues
Closes #XXX

## Testing
Describe testing performed:
- Unit tests: ...
- Integration tests: ...
- Performance tests: ...

## Performance Impact
Describe performance impact with metrics:
- Before: X TPS, Y ms p95
- After: X TPS, Y ms p95
- Benchmark results: link to report

## Checklist
- [ ] Tests pass
- [ ] Performance targets met
- [ ] Documentation updated
- [ ] Database migrations tested
- [ ] Observability added
```

### Review Process

1. **Automated Checks**: CI must pass
2. **Code Review**: At least one approval required
3. **Performance Review**: Benchmarks must pass thresholds
4. **Security Review**: No vulnerabilities introduced

### Merge Strategy

- **Squash and merge** for feature branches
- **Rebase and merge** for hotfixes
- Ensure commit message follows conventional commits

## Observability Requirements

### Logging

Use structured logging with correlation IDs:

```java
@Slf4j
public class TransferService {

    public TransferResult transfer(Long from, Long to, BigDecimal amount) {
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);

        log.info("Starting transfer: from={}, to={}, amount={}", from, to, amount);

        try {
            // Implementation
            log.info("Transfer completed successfully");
        } catch (Exception e) {
            log.error("Transfer failed", e);
            throw e;
        } finally {
            MDC.remove("correlationId");
        }
    }
}
```

### Metrics

Add Micrometer metrics for business events:

```java
@Service
public class TransferService {

    private final Counter transferCounter;
    private final Timer transferTimer;

    public TransferService(MeterRegistry registry) {
        this.transferCounter = Counter.builder("transfers.total")
            .tag("type", "money_transfer")
            .register(registry);

        this.transferTimer = Timer.builder("transfers.duration")
            .register(registry);
    }

    public TransferResult transfer(Long from, Long to, BigDecimal amount) {
        return transferTimer.record(() -> {
            TransferResult result = performTransfer(from, to, amount);
            transferCounter.increment();
            return result;
        });
    }
}
```

### Distributed Tracing

Use OpenTelemetry for tracing:

```java
@Service
public class TransferService {

    @WithSpan("transfer")
    public TransferResult transfer(
        @SpanAttribute("from_account") Long from,
        @SpanAttribute("to_account") Long to,
        @SpanAttribute("amount") BigDecimal amount
    ) {
        // Implementation
    }
}
```

## Security Guidelines

1. **Input Validation**: Validate all user inputs
2. **SQL Injection**: Use parameterized queries or JPA
3. **Sensitive Data**: Never log passwords, tokens, PII
4. **Dependencies**: Keep dependencies up to date
5. **Secrets**: Use environment variables, never hardcode

## Database Migrations

### Flyway Conventions

- Version format: `V{version}__{description}.sql`
- Example: `V001__create_accounts_table.sql`
- Always provide rollback in comments
- Test both up and down migrations

### Migration Template

```sql
-- V001__create_accounts_table.sql
-- Description: Create accounts table with ACID-compliant schema
-- Rollback: DROP TABLE IF EXISTS accounts;

CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_type_id BIGINT NOT NULL,
    balance NUMERIC(19, 2) NOT NULL DEFAULT 0.00,
    version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_accounts_users FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_accounts_types FOREIGN KEY (account_type_id) REFERENCES account_types(id),
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0.00)
);

CREATE INDEX idx_accounts_user_id ON accounts(user_id);
CREATE INDEX idx_accounts_status ON accounts(status) WHERE status = 'ACTIVE';
```

## Questions?

- Read the [constitution](.specify/memory/constitution.md)
- Check [existing documentation](docs/)
- Ask in GitHub Discussions
- Review [specification](specs/001-oltp-core-demo/spec.md)

## License

By contributing, you agree that your contributions will be licensed under the same license as the project (MIT License).

---

Thank you for contributing to building production-grade OLTP demonstrations!
