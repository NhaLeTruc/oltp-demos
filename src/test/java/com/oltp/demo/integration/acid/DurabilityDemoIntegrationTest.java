package com.oltp.demo.integration.acid;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.oltp.demo.integration.BaseIntegrationTest;
import com.oltp.demo.service.acid.DurabilityDemoService;

/**
 * Integration tests for Durability demonstrations.
 *
 * Tests verify that:
 * - Committed transactions are durable (survive restarts)
 * - Transfer logs provide immutable audit trail
 * - Correlation IDs enable end-to-end tracing
 * - Recovery verification works correctly
 *
 * Uses real PostgreSQL with WAL to test actual durability.
 */
@SpringBootTest
class DurabilityDemoIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DurabilityDemoService durabilityService;

    @Test
    void demonstrateDurableCommit_ShouldPersist() {
        // Given
        UUID correlationId = UUID.randomUUID();

        // When
        DurabilityDemoService.DurabilityResult result =
            durabilityService.demonstrateDurableCommit(correlationId);

        // Then
        assertThat(result.committed()).isTrue();
        assertThat(result.correlationId()).isEqualTo(correlationId);
        assertThat(result.transactionId()).isNotNull();
        assertThat(result.transferLogId()).isNotNull();
        assertThat(result.commitTime()).isNotNull();
        assertThat(result.message()).contains("durably persisted");
    }

    @Test
    void verifyCrashRecovery_ShouldFindCommittedTransaction() {
        // Given - commit a transaction first
        UUID correlationId = UUID.randomUUID();
        DurabilityDemoService.DurabilityResult commitResult =
            durabilityService.demonstrateDurableCommit(correlationId);

        assertThat(commitResult.committed()).isTrue();

        // When - verify it can be recovered (simulating post-restart)
        DurabilityDemoService.RecoveryVerificationResult recoveryResult =
            durabilityService.verifyCrashRecovery(correlationId);

        // Then
        assertThat(recoveryResult.dataRecovered()).isTrue();
        assertThat(recoveryResult.correlationId()).isEqualTo(correlationId);
        assertThat(recoveryResult.transactionId()).isEqualTo(commitResult.transactionId());
        assertThat(recoveryResult.logCount()).isGreaterThan(0);
        assertThat(recoveryResult.message()).contains("DURABLE");
    }

    @Test
    void getRecentCompletedTransactions_ShouldReturnTransactions() {
        // Given - create some completed transactions
        UUID correlationId1 = UUID.randomUUID();
        UUID correlationId2 = UUID.randomUUID();

        durabilityService.demonstrateDurableCommit(correlationId1);
        durabilityService.demonstrateDurableCommit(correlationId2);

        // When
        List<DurabilityDemoService.CompletedTransactionInfo> transactions =
            durabilityService.getRecentCompletedTransactions(5);

        // Then
        assertThat(transactions).isNotEmpty();
        assertThat(transactions).anySatisfy(txn ->
            assertThat(txn.correlationId()).isEqualTo(correlationId1)
        );
        assertThat(transactions).anySatisfy(txn ->
            assertThat(txn.correlationId()).isEqualTo(correlationId2)
        );
    }

    @Test
    void getTransferAuditTrail_ShouldReturnLogs() {
        // Given
        UUID correlationId = UUID.randomUUID();
        DurabilityDemoService.DurabilityResult commitResult =
            durabilityService.demonstrateDurableCommit(correlationId);

        // When
        List<DurabilityDemoService.TransferLogInfo> logs =
            durabilityService.getTransferAuditTrail(correlationId);

        // Then
        assertThat(logs).isNotEmpty();
        assertThat(logs).allSatisfy(log ->
            assertThat(log.correlationId()).isEqualTo(correlationId)
        );
    }

    @Test
    void countCompletedTransactions_ShouldReturnCount() {
        // Given
        long initialCount = durabilityService.countCompletedTransactions();

        // Create new transaction
        durabilityService.demonstrateDurableCommit(UUID.randomUUID());

        // When
        long finalCount = durabilityService.countCompletedTransactions();

        // Then
        assertThat(finalCount).isEqualTo(initialCount + 1);
    }

    @Test
    void verifyCrashRecovery_ForNonExistentTransaction_ShouldReturnNotRecovered() {
        // Given
        UUID nonExistentCorrelationId = UUID.randomUUID();

        // When
        DurabilityDemoService.RecoveryVerificationResult result =
            durabilityService.verifyCrashRecovery(nonExistentCorrelationId);

        // Then
        assertThat(result.dataRecovered()).isFalse();
        assertThat(result.message()).contains("not found");
    }
}
