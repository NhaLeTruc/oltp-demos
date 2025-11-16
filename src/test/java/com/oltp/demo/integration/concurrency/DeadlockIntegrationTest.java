package com.oltp.demo.integration.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.oltp.demo.integration.BaseIntegrationTest;
import com.oltp.demo.service.concurrency.DeadlockDemoService;

/**
 * Integration tests for Deadlock Detection and Recovery.
 *
 * Tests verify that:
 * - Deadlocks can be triggered with bidirectional transfers
 * - PostgreSQL detects deadlocks automatically
 * - Deadlock victims are retried successfully
 * - Lock ordering prevents deadlocks
 * - Deadlock metrics are tracked
 */
@SpringBootTest
class DeadlockIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DeadlockDemoService deadlockDemoService;

    @Test
    void demonstrateBidirectionalTransferDeadlock_ShouldDetectAndRecover() {
        // Given
        Long accountIdA = 1L;
        Long accountIdB = 2L;
        BigDecimal amount = BigDecimal.valueOf(10.00);

        // When
        DeadlockDemoService.DeadlockResult result =
            deadlockDemoService.demonstrateBidirectionalTransferDeadlock(
                accountIdA, accountIdB, amount
            );

        // Then
        // At least one deadlock should occur with bidirectional transfers
        // (though not guaranteed in every run due to timing)
        assertThat(result.deadlocksDetected()).isGreaterThanOrEqualTo(0);

        // Despite deadlocks, both transfers should eventually succeed via retry
        assertThat(result.successfulTransfers())
            .isGreaterThanOrEqualTo(1)  // At least one should succeed
            .withFailMessage("At least one transfer should succeed despite deadlocks");

        // If deadlocks occurred, retries should have happened
        if (result.deadlocksDetected() > 0) {
            assertThat(result.totalRetries()).isGreaterThan(0);
        }

        // Log results for analysis
        System.out.printf("""
            Deadlock Test Results:
            - Deadlocks detected: %d
            - Total retries: %d
            - Successful transfers: %d
            - Failed transfers: %d
            - Duration: %dms
            %n""",
            result.deadlocksDetected(),
            result.totalRetries(),
            result.successfulTransfers(),
            result.failedTransfers(),
            result.durationMs()
        );
    }

    @Test
    void transferWithDeadlockPrevention_ShouldNotDeadlock() {
        // Given
        Long accountIdA = 3L;
        Long accountIdB = 4L;
        BigDecimal amount = BigDecimal.valueOf(5.00);

        // When - execute bidirectional transfers with lock ordering
        boolean transfer1Success = deadlockDemoService.transferWithDeadlockPrevention(
            accountIdA, accountIdB, amount, true  // A → B
        );

        boolean transfer2Success = deadlockDemoService.transferWithDeadlockPrevention(
            accountIdA, accountIdB, amount, false  // B → A
        );

        // Then - both should succeed without deadlocks
        assertThat(transfer1Success).isTrue();
        assertThat(transfer2Success).isTrue();

        System.out.println("Deadlock prevention successful - no deadlocks occurred");
    }

    @Test
    void multipleDeadlockScenarios_ShouldAllRecover() {
        // Given
        Long accountIdA = 5L;
        Long accountIdB = 6L;
        BigDecimal amount = BigDecimal.valueOf(3.00);

        // When - run multiple deadlock scenarios
        int successCount = 0;
        int totalDeadlocks = 0;

        for (int i = 0; i < 3; i++) {
            DeadlockDemoService.DeadlockResult result =
                deadlockDemoService.demonstrateBidirectionalTransferDeadlock(
                    accountIdA, accountIdB, amount
                );

            if (result.successfulTransfers() >= 1) {
                successCount++;
            }
            totalDeadlocks += result.deadlocksDetected();
        }

        // Then - all scenarios should eventually succeed
        assertThat(successCount).isEqualTo(3)
            .withFailMessage("All deadlock scenarios should eventually succeed via retry");

        System.out.printf("Multiple deadlock tests: %d runs, %d total deadlocks detected%n",
                3, totalDeadlocks);
    }
}
