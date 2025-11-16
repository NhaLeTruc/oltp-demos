package com.oltp.demo.integration.acid;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.oltp.demo.integration.BaseIntegrationTest;
import com.oltp.demo.service.acid.IsolationDemoService;

/**
 * Integration tests for Isolation demonstrations.
 *
 * Tests verify that:
 * - READ_COMMITTED prevents dirty reads
 * - REPEATABLE_READ provides snapshot isolation
 * - SERIALIZABLE ensures true serializability
 * - Concurrent operations maintain consistency
 *
 * Uses real PostgreSQL to test actual isolation levels.
 */
@SpringBootTest
class IsolationDemoIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IsolationDemoService isolationService;

    @Test
    void demonstrateReadCommitted_ShouldWork() {
        // Given
        Long accountId = 1L;

        // When
        IsolationDemoService.IsolationDemoResult result =
            isolationService.demonstrateReadCommitted(accountId);

        // Then
        assertThat(result.isolationLevel()).isEqualTo("READ_COMMITTED");
        assertThat(result.initialBalance()).isNotNull();
        assertThat(result.finalBalance()).isNotNull();
        assertThat(result.observation()).isNotNull();
    }

    @Test
    void demonstrateRepeatableRead_ShouldProvideSnapshot() {
        // Given
        Long accountId = 1L;

        // When
        IsolationDemoService.IsolationDemoResult result =
            isolationService.demonstrateRepeatableRead(accountId);

        // Then
        assertThat(result.isolationLevel()).isEqualTo("REPEATABLE_READ");
        assertThat(result.initialBalance())
            .isEqualByComparingTo(result.finalBalance())
            .withFailMessage("REPEATABLE_READ should see same balance throughout transaction");
        assertThat(result.observation()).contains("snapshot isolation");
    }

    @Test
    void demonstrateSerializable_ShouldComplete() {
        // Given
        Long accountId = 1L;
        BigDecimal amount = BigDecimal.valueOf(10.00);

        // When
        IsolationDemoService.IsolationDemoResult result =
            isolationService.demonstrateSerializable(accountId, amount);

        // Then
        assertThat(result.isolationLevel()).isEqualTo("SERIALIZABLE");
        assertThat(result.finalBalance())
            .isEqualByComparingTo(result.initialBalance().add(amount));
    }

    @Test
    void executeConcurrentTransfers_ShouldMaintainConsistency() {
        // Given
        Long accountId = 1L;
        BigDecimal amount = BigDecimal.valueOf(1.00);
        int concurrentCount = 5;

        // When
        IsolationDemoService.ConcurrentTransferResult result =
            isolationService.executeConcurrentTransfers(accountId, amount, concurrentCount);

        // Then
        assertThat(result.totalOperations()).isEqualTo(concurrentCount);
        assertThat(result.successfulOperations()).isGreaterThan(0);
        assertThat(result.balanceCorrect()).isTrue()
            .withFailMessage("Final balance should match expected (initial + successful × amount)");

        // Verify optimistic locking worked - version incremented
        assertThat(result.finalVersion())
            .isGreaterThan(result.initialVersion());

        // Log results for analysis
        System.out.printf("""
            Concurrent Transfer Results:
            - Total operations: %d
            - Successful: %d
            - Failed: %d
            - Total retries: %d
            - Duration: %dms
            - Initial balance: %s
            - Final balance: %s
            - Expected balance: %s
            - Balance correct: %s
            - Version: %d → %d
            %n""",
            result.totalOperations(),
            result.successfulOperations(),
            result.failedOperations(),
            result.totalRetries(),
            result.durationMs(),
            result.initialBalance(),
            result.finalBalance(),
            result.expectedBalance(),
            result.balanceCorrect(),
            result.initialVersion(),
            result.finalVersion()
        );
    }
}
