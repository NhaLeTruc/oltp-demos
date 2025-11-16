package com.oltp.demo.integration.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.oltp.demo.integration.BaseIntegrationTest;
import com.oltp.demo.service.concurrency.OptimisticLockingService;

/**
 * Integration tests for Optimistic Locking demonstrations.
 *
 * Tests verify that:
 * - Concurrent updates with @Version work correctly
 * - Optimistic lock exceptions are detected and retried
 * - Final balance is correct despite conflicts
 * - Retry logic with exponential backoff works
 * - Conflict rates are tracked
 */
@SpringBootTest
class OptimisticLockingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OptimisticLockingService optimisticLockingService;

    @Test
    void executeConcurrentUpdates_ShouldHandleOptimisticLockConflicts() {
        // Given
        Long accountId = 1L;
        BigDecimal amount = BigDecimal.valueOf(1.00);
        int concurrentOperations = 10;

        // When
        OptimisticLockingService.OptimisticLockingResult result =
            optimisticLockingService.executeConcurrentUpdates(accountId, amount, concurrentOperations);

        // Then
        assertThat(result.totalOperations()).isEqualTo(concurrentOperations);
        assertThat(result.successfulOperations()).isGreaterThan(0);
        assertThat(result.balanceCorrect()).isTrue()
            .withFailMessage("Final balance should equal initial + (amount × successful operations)");

        // Verify version increments match successful operations
        assertThat(result.versionIncrements()).isEqualTo(result.successfulOperations());

        // Verify some conflicts occurred (with 10 concurrent operations, conflicts are likely)
        if (result.optimisticLockExceptions() > 0) {
            assertThat(result.totalRetries()).isGreaterThan(0);
            assertThat(result.conflictRate()).isGreaterThan(0);
        }

        // Log results for analysis
        System.out.printf("""
            Optimistic Locking Results:
            - Total operations: %d
            - Successful: %d
            - Failed: %d
            - Total retries: %d
            - Avg retries/operation: %.2f
            - Optimistic lock exceptions: %d
            - Conflict rate: %.1f%%
            - Balance correct: %s
            - Duration: %dms
            %n""",
            result.totalOperations(),
            result.successfulOperations(),
            result.failedOperations(),
            result.totalRetries(),
            result.avgRetriesPerOperation(),
            result.optimisticLockExceptions(),
            result.conflictRate(),
            result.balanceCorrect(),
            result.durationMs()
        );
    }

    @Test
    void updateWithVersionCheck_ShouldIncrementVersion() {
        // Given
        Long accountId = 2L;
        BigDecimal amount = BigDecimal.valueOf(50.00);

        // When
        OptimisticLockingService.VersionedUpdateResult result =
            optimisticLockingService.updateWithVersionCheck(accountId, amount);

        // Then
        assertThat(result.success()).isTrue();
        assertThat(result.versionAfter()).isEqualTo(result.versionBefore() + 1);
        assertThat(result.balanceAfter()).isEqualByComparingTo(
            result.balanceBefore().add(amount)
        );
    }

    @Test
    void concurrentUpdates_WithHighContention_ShouldEventuallySucceed() {
        // Given - high contention scenario
        Long accountId = 3L;
        BigDecimal amount = BigDecimal.valueOf(0.50);
        int concurrentOperations = 20;  // Higher contention

        // When
        OptimisticLockingService.OptimisticLockingResult result =
            optimisticLockingService.executeConcurrentUpdates(accountId, amount, concurrentOperations);

        // Then - despite high contention, all or most should eventually succeed
        assertThat(result.successfulOperations())
            .isGreaterThanOrEqualTo((int) (concurrentOperations * 0.8))  // At least 80% success
            .withFailMessage("Most operations should succeed despite retries");

        assertThat(result.balanceCorrect()).isTrue();

        // Higher contention should result in more conflicts and retries
        assertThat(result.optimisticLockExceptions()).isGreaterThan(0);
        assertThat(result.totalRetries()).isGreaterThan(0);
    }
}
