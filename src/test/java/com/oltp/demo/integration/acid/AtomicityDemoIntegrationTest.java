package com.oltp.demo.integration.acid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.oltp.demo.domain.Account;
import com.oltp.demo.integration.BaseIntegrationTest;
import com.oltp.demo.repository.AccountRepository;
import com.oltp.demo.repository.TransactionRepository;
import com.oltp.demo.repository.TransferLogRepository;
import com.oltp.demo.service.acid.AtomicityDemoService;

/**
 * Integration tests for Atomicity demonstrations.
 *
 * Tests verify that:
 * - Successful transfers complete atomically (all-or-nothing)
 * - Failed transfers roll back completely
 * - Mid-transaction failures don't leave partial state
 * - Database state remains consistent
 *
 * Uses real PostgreSQL via Testcontainers for authentic ACID testing.
 */
@SpringBootTest
class AtomicityDemoIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AtomicityDemoService atomicityService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransferLogRepository transferLogRepository;

    private Account sourceAccount;
    private Account destAccount;

    @BeforeEach
    void setUp() {
        // Clean up previous test data
        transferLogRepository.deleteAll();
        transactionRepository.deleteAll();

        // Create test accounts
        sourceAccount = accountRepository.findById(1L)
            .orElseThrow(() -> new IllegalStateException("Test data not seeded"));
        destAccount = accountRepository.findById(2L)
            .orElseThrow(() -> new IllegalStateException("Test data not seeded"));
    }

    @Test
    void successfulTransfer_ShouldCompleteAtomically() {
        // Given
        BigDecimal transferAmount = BigDecimal.valueOf(50.00);
        BigDecimal initialSourceBalance = sourceAccount.getBalance();
        BigDecimal initialDestBalance = destAccount.getBalance();

        // When
        AtomicityDemoService.TransferResult result =
            atomicityService.successfulTransfer(
                sourceAccount.getId(),
                destAccount.getId(),
                transferAmount
            );

        // Then
        assertThat(result.success()).isTrue();
        assertThat(result.transactionId()).isNotNull();
        assertThat(result.correlationId()).isNotNull();

        // Verify account balances updated atomically
        Account updatedSource = accountRepository.findById(sourceAccount.getId()).orElseThrow();
        Account updatedDest = accountRepository.findById(destAccount.getId()).orElseThrow();

        assertThat(updatedSource.getBalance())
            .isEqualByComparingTo(initialSourceBalance.subtract(transferAmount));
        assertThat(updatedDest.getBalance())
            .isEqualByComparingTo(initialDestBalance.add(transferAmount));

        // Verify transaction recorded
        assertThat(transactionRepository.findById(result.transactionId()))
            .isPresent()
            .get()
            .satisfies(txn -> {
                assertThat(txn.getStatus()).isEqualTo(com.oltp.demo.domain.Transaction.TransactionStatus.COMPLETED);
                assertThat(txn.getAmount()).isEqualByComparingTo(transferAmount);
            });

        // Verify transfer logs created
        assertThat(transferLogRepository.findByCorrelationId(
            java.util.UUID.fromString(result.correlationId())
        )).hasSize(2); // INITIATED and COMPLETED
    }

    @Test
    void failedTransferInsufficientFunds_ShouldRollbackCompletely() {
        // Given
        BigDecimal excessiveAmount = BigDecimal.valueOf(999999.00);
        BigDecimal initialSourceBalance = sourceAccount.getBalance();
        BigDecimal initialDestBalance = destAccount.getBalance();
        long initialTxnCount = transactionRepository.count();
        long initialLogCount = transferLogRepository.count();

        // When & Then
        assertThatThrownBy(() ->
            atomicityService.failedTransferInsufficientFunds(
                sourceAccount.getId(),
                destAccount.getId(),
                excessiveAmount
            )
        ).isInstanceOf(AtomicityDemoService.InsufficientFundsException.class);

        // Verify complete rollback - no state changes
        Account unchangedSource = accountRepository.findById(sourceAccount.getId()).orElseThrow();
        Account unchangedDest = accountRepository.findById(destAccount.getId()).orElseThrow();

        assertThat(unchangedSource.getBalance()).isEqualByComparingTo(initialSourceBalance);
        assertThat(unchangedDest.getBalance()).isEqualByComparingTo(initialDestBalance);

        // Verify no transaction or logs persisted
        assertThat(transactionRepository.count()).isEqualTo(initialTxnCount);
        assertThat(transferLogRepository.count()).isEqualTo(initialLogCount);
    }

    @Test
    void midTransactionFailure_ShouldRollbackDebit() {
        // Given
        BigDecimal transferAmount = BigDecimal.valueOf(30.00);
        BigDecimal initialSourceBalance = sourceAccount.getBalance();
        BigDecimal initialDestBalance = destAccount.getBalance();

        // When & Then - simulate failure after debit
        assertThatThrownBy(() ->
            atomicityService.transferWithMidTransactionFailure(
                sourceAccount.getId(),
                destAccount.getId(),
                transferAmount,
                true  // Simulate failure
            )
        ).isInstanceOf(RuntimeException.class)
         .hasMessageContaining("Simulated system failure");

        // Verify debit was rolled back - source balance unchanged
        Account unchangedSource = accountRepository.findById(sourceAccount.getId()).orElseThrow();
        Account unchangedDest = accountRepository.findById(destAccount.getId()).orElseThrow();

        assertThat(unchangedSource.getBalance())
            .isEqualByComparingTo(initialSourceBalance)
            .withFailMessage("Source balance should be rolled back to initial value");

        assertThat(unchangedDest.getBalance())
            .isEqualByComparingTo(initialDestBalance)
            .withFailMessage("Dest balance should remain unchanged");
    }

    @Test
    void midTransactionFailure_WithoutFailureSimulation_ShouldSucceed() {
        // Given
        BigDecimal transferAmount = BigDecimal.valueOf(25.00);
        BigDecimal initialSourceBalance = sourceAccount.getBalance();
        BigDecimal initialDestBalance = destAccount.getBalance();

        // When - no failure simulation
        AtomicityDemoService.TransferResult result =
            atomicityService.transferWithMidTransactionFailure(
                sourceAccount.getId(),
                destAccount.getId(),
                transferAmount,
                false  // No failure
            );

        // Then
        assertThat(result.success()).isTrue();

        // Verify transfer completed
        Account updatedSource = accountRepository.findById(sourceAccount.getId()).orElseThrow();
        Account updatedDest = accountRepository.findById(destAccount.getId()).orElseThrow();

        assertThat(updatedSource.getBalance())
            .isEqualByComparingTo(initialSourceBalance.subtract(transferAmount));
        assertThat(updatedDest.getBalance())
            .isEqualByComparingTo(initialDestBalance.add(transferAmount));
    }
}
