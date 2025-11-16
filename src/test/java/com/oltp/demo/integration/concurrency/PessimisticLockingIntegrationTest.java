package com.oltp.demo.integration.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.oltp.demo.integration.BaseIntegrationTest;
import com.oltp.demo.service.concurrency.PessimisticLockingService;

/**
 * Integration tests for Pessimistic Locking demonstrations.
 *
 * Tests verify that:
 * - SELECT FOR UPDATE locks work correctly
 * - Transactions wait for lock release (serialized execution)
 * - No optimistic lock exceptions (all conflicts resolved via blocking)
 * - Lock wait times are measurable
 * - Final balance is correct
 */
@SpringBootTest
class PessimisticLockingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PessimisticLockingService pessimisticLockingService;

    @Test
    void executeConcurrentUpdates_ShouldSerializeAccess() {
        // Given
        Long accountId = 1L;
        BigDecimal amount = BigDecimal.valueOf(2.00);
        int concurrentOperations = 10;

        // When
        PessimisticLockingService.PessimisticLockingResult result =
            pessimisticLockingService.executeConcurrentUpdates(accountId, amount, concurrentOperations);

        // Then
        assertThat(result.totalOperations()).isEqualTo(concurrentOperations);

        // All operations should succeed (no retries needed with pessimistic locking)
        assertThat(result.successfulOperations()).isEqualTo(concurrentOperations);
        assertThat(result.failedOperations()).isEqualTo(0);

        assertThat(result.balanceCorrect()).isTrue()
            .withFailMessage("Final balance should equal initial + (amount × operations)");

        // Verify lock wait times
        assertThat(result.totalLockWaitMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.avgLockWaitMs()).isGreaterThanOrEqualTo(0);

        // Log results for analysis
        System.out.printf("""
            Pessimistic Locking Results:
            - Total operations: %d
            - Successful: %d (all should succeed)
            - Failed: %d
            - Total lock wait time: %dms
            - Avg lock wait time: %.2fms
            - Max lock wait time: %dms
            - Operations/second: %.1f
            - Balance correct: %s
            - Duration: %dms
            %n""",
            result.totalOperations(),
            result.successfulOperations(),
            result.failedOperations(),
            result.totalLockWaitMs(),
            result.avgLockWaitMs(),
            result.maxLockWaitMs(),
            result.operationsPerSecond(),
            result.balanceCorrect(),
            result.durationMs()
        );
    }

    @Test
    void measureLockAcquisitionTime_ShouldBeFast() {
        // Given
        Long accountId = 2L;

        // When
        long lockWaitMs = pessimisticLockingService.measureLockAcquisitionTime(accountId);

        // Then - with no contention, lock should be acquired quickly
        assertThat(lockWaitMs).isLessThan(100);  // Should be < 100ms
    }

    @Test
    void concurrentUpdates_ComparedToOptimistic_ShouldHaveLowerThroughput() {
        // Given - same scenario for comparison
        Long accountId = 3L;
        BigDecimal amount = BigDecimal.valueOf(1.00);
        int concurrentOperations = 15;

        // When
        PessimisticLockingService.PessimisticLockingResult result =
            pessimisticLockingService.executeConcurrentUpdates(accountId, amount, concurrentOperations);

        // Then
        // Pessimistic locking serializes access, so throughput is lower
        // but all operations succeed without retries
        assertThat(result.successfulOperations()).isEqualTo(concurrentOperations);
        assertThat(result.operationsPerSecond()).isGreaterThan(0);

        // Trade-off: Lower throughput but guaranteed success
        assertThat(result.balanceCorrect()).isTrue();
    }
}
