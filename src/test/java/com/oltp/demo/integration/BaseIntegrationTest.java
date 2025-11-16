package com.oltp.demo.integration;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests with Testcontainers.
 *
 * Provides:
 * - PostgreSQL container for database integration tests
 * - Spring Boot test context configuration
 * - Dynamic property configuration for test database
 *
 * All integration tests should extend this class to benefit from:
 * - Real PostgreSQL database (not H2 or mocks)
 * - Automatic database schema creation via Flyway
 * - Container reuse across tests (faster execution)
 * - Isolation between test runs
 *
 * Example usage:
 * <pre>
 * {@code
 * class TransferServiceIntegrationTest extends BaseIntegrationTest {
 *     @Autowired
 *     private TransferService transferService;
 *
 *     @Test
 *     void testTransfer() {
 *         // Test with real database
 *     }
 * }
 * }
 * </pre>
 *
 * @see <a href="https://www.testcontainers.org/">Testcontainers Documentation</a>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class BaseIntegrationTest {

    /**
     * PostgreSQL container shared across all integration tests.
     *
     * Configuration:
     * - Image: postgres:15-alpine (matches production)
     * - Database: testdb
     * - Reuse enabled: true (via testcontainers.properties)
     *
     * The container starts once and is reused across all test classes,
     * significantly improving test execution speed.
     */
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test")
        .withReuse(true);  // Reuse container across test runs

    /**
     * Starts PostgreSQL container before any tests run.
     */
    @BeforeAll
    static void beforeAll() {
        postgres.start();
    }

    /**
     * Configures Spring Boot to use Testcontainers PostgreSQL.
     *
     * Dynamically sets:
     * - spring.datasource.url
     * - spring.datasource.username
     * - spring.datasource.password
     *
     * @param registry Spring's dynamic property registry
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Disable Redis for tests (unless specifically testing caching)
        registry.add("spring.cache.type", () -> "none");
    }
}
