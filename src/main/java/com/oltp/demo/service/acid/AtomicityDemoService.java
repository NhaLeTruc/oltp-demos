package com.oltp.demo.service.acid;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oltp.demo.domain.Account;
import com.oltp.demo.domain.Transaction;
import com.oltp.demo.domain.TransferLog;
import com.oltp.demo.repository.AccountRepository;
import com.oltp.demo.repository.TransactionRepository;
import com.oltp.demo.repository.TransferLogRepository;
import com.oltp.demo.util.CorrelationIdFilter;
import com.oltp.demo.util.MetricsHelper;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating ACID Atomicity property.
 *
 * Atomicity: "All or Nothing" - A transaction either completes fully or
 * has no effect at all. Partial updates are never visible.
 *
 * Demonstrations:
 * 1. Successful transfer: Both debit and credit complete atomically
 * 2. Failed transfer: Insufficient funds causes complete rollback
 * 3. Mid-transaction failure: Exception causes automatic rollback
 *
 * Key mechanisms:
 * - Spring @Transactional annotation
 * - Automatic rollback on RuntimeException
 * - Manual rollback via TransactionStatus
 * - Transaction status tracking in database
 *
 * Constitution.md alignment:
 * - Principle I: Data Integrity First - Never compromise transactional integrity
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US1: ACID Transaction Guarantees</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AtomicityDemoService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransferLogRepository transferLogRepository;
    private final MetricsHelper metricsHelper;

    /**
     * Demonstrates successful atomic transfer.
     *
     * Both debit and credit operations complete together or not at all.
     * If any part fails, the entire transaction rolls back.
     *
     * Steps:
     * 1. Create transaction record (PENDING)
     * 2. Debit from source account
     * 3. Credit to destination account
     * 4. Log transfer
     * 5. Mark transaction COMPLETED
     * 6. Commit all changes atomically
     *
     * @param fromAccountId source account
     * @param toAccountId destination account
     * @param amount transfer amount
     * @return transaction result with correlation ID
     * @throws IllegalArgumentException if accounts not found or amount invalid
     * @throws InsufficientFundsException if balance insufficient
     */
    @Transactional
    @WithSpan("atomicity.transfer.successful")  // T152: Add OpenTelemetry span
    public TransferResult successfulTransfer(Long fromAccountId, Long toAccountId, BigDecimal amount) {
        long startTime = System.currentTimeMillis();
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();

        // T152: Add span attributes for distributed tracing
        Span currentSpan = Span.current();
        currentSpan.setAttribute("transfer.from_account_id", fromAccountId);
        currentSpan.setAttribute("transfer.to_account_id", toAccountId);
        currentSpan.setAttribute("transfer.amount", amount.toString());
        currentSpan.setAttribute("correlation.id", correlationId != null ? correlationId : "none");

        log.info("Starting atomic transfer: from={}, to={}, amount={}, correlationId={}",
                fromAccountId, toAccountId, amount, correlationId);

        try {
            // Validate amount
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Amount must be positive");
            }

            // Find accounts
            Account fromAccount = accountRepository.findById(fromAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Source account not found: " + fromAccountId));
            Account toAccount = accountRepository.findById(toAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Destination account not found: " + toAccountId));

            // Create transaction record
            Transaction txn = Transaction.builder()
                .fromAccount(fromAccount)
                .toAccount(toAccount)
                .amount(amount)
                .transactionType(Transaction.TransactionType.TRANSFER)
                .status(Transaction.TransactionStatus.PENDING)
                .correlationId(UUID.fromString(correlationId != null ? correlationId : UUID.randomUUID().toString()))
                .build();
            txn = transactionRepository.save(txn);

            // Log transfer initiation
            TransferLog initiatedLog = TransferLog.builder()
                .transaction(txn)
                .fromAccount(fromAccount)
                .toAccount(toAccount)
                .amount(amount)
                .status(TransferLog.TransferStatus.INITIATED)
                .correlationId(txn.getCorrelationId())
                .build();
            transferLogRepository.save(initiatedLog);

            // ATOMICITY DEMONSTRATION: Both operations must succeed
            // If either fails, Spring rolls back the entire transaction

            // Debit from source
            if (!fromAccount.hasSufficientBalance(amount)) {
                throw new InsufficientFundsException(
                    String.format("Insufficient funds: balance=%s, required=%s",
                        fromAccount.getBalance(), amount)
                );
            }
            fromAccount.debit(amount);
            accountRepository.save(fromAccount);

            // Credit to destination
            toAccount.credit(amount);
            accountRepository.save(toAccount);

            // Mark transaction completed
            txn.markCompleted();
            transactionRepository.save(txn);

            // Log successful completion
            TransferLog completedLog = TransferLog.builder()
                .transaction(txn)
                .fromAccount(fromAccount)
                .toAccount(toAccount)
                .amount(amount)
                .status(TransferLog.TransferStatus.COMPLETED)
                .correlationId(txn.getCorrelationId())
                .build();
            transferLogRepository.save(completedLog);

            long duration = System.currentTimeMillis() - startTime;
            metricsHelper.recordTransfer("TRANSFER", true, duration);

            log.info("Transfer completed successfully: txnId={}, duration={}ms", txn.getId(), duration);

            return TransferResult.success(txn.getCorrelationId().toString(), txn.getId(), duration);

        } catch (Exception e) {
            log.error("Transfer failed, transaction will rollback: {}", e.getMessage());
            long duration = System.currentTimeMillis() - startTime;
            metricsHelper.recordTransfer("TRANSFER", false, duration);
            throw e; // Triggers automatic rollback
        }
    }

    /**
     * Demonstrates atomicity with rollback on insufficient funds.
     *
     * When the balance check fails, the entire transaction rolls back:
     * - Transaction record is not created
     * - No changes to account balances
     * - No transfer logs are persisted
     *
     * This demonstrates that failed transactions have ZERO effect on the database.
     *
     * @param fromAccountId source account
     * @param toAccountId destination account
     * @param amount transfer amount (should exceed balance)
     * @return transfer result (will throw exception)
     * @throws InsufficientFundsException always (demonstrates rollback)
     */
    @Transactional
    @WithSpan("atomicity.transfer.insufficient_funds")  // T152: Add OpenTelemetry span
    public TransferResult failedTransferInsufficientFunds(Long fromAccountId, Long toAccountId, BigDecimal amount) {
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();

        // T152: Add span attributes
        Span currentSpan = Span.current();
        currentSpan.setAttribute("transfer.from_account_id", fromAccountId);
        currentSpan.setAttribute("transfer.to_account_id", toAccountId);
        currentSpan.setAttribute("transfer.amount", amount.toString());
        currentSpan.setAttribute("transfer.expected_failure", "insufficient_funds");

        log.info("Demonstrating rollback on insufficient funds: from={}, to={}, amount={}",
                fromAccountId, toAccountId, amount);

        // Find accounts
        Account fromAccount = accountRepository.findById(fromAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Source account not found"));
        Account toAccount = accountRepository.findById(toAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Destination account not found"));

        // Create transaction record (will be rolled back)
        Transaction txn = Transaction.builder()
            .fromAccount(fromAccount)
            .toAccount(toAccount)
            .amount(amount)
            .transactionType(Transaction.TransactionType.TRANSFER)
            .status(Transaction.TransactionStatus.PENDING)
            .correlationId(UUID.fromString(correlationId != null ? correlationId : UUID.randomUUID().toString()))
            .build();
        transactionRepository.save(txn);

        // Check balance and throw exception
        if (!fromAccount.hasSufficientBalance(amount)) {
            // Mark transaction as failed (this will also be rolled back)
            txn.markFailed("Insufficient funds");
            transactionRepository.save(txn);

            throw new InsufficientFundsException(
                String.format("Insufficient funds: balance=%s, required=%s",
                    fromAccount.getBalance(), amount)
            );
        }

        // This code is never reached
        return TransferResult.failure("Should not reach here");
    }

    /**
     * Demonstrates atomicity with mid-transaction exception.
     *
     * Simulates a failure after debit but before credit.
     * Without atomicity, this would result in:
     * - Money debited from source ✓
     * - Money NOT credited to destination ✗
     * - Lost money! 💸
     *
     * With atomicity (@Transactional), the debit is automatically rolled back.
     *
     * @param fromAccountId source account
     * @param toAccountId destination account
     * @param amount transfer amount
     * @param simulateFailureAfterDebit if true, throws exception after debit
     * @return transfer result
     */
    @Transactional
    @WithSpan("atomicity.transfer.mid_transaction_failure")  // T152: Add OpenTelemetry span
    public TransferResult transferWithMidTransactionFailure(
            Long fromAccountId,
            Long toAccountId,
            BigDecimal amount,
            boolean simulateFailureAfterDebit) {

        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();

        // T152: Add span attributes
        Span currentSpan = Span.current();
        currentSpan.setAttribute("transfer.from_account_id", fromAccountId);
        currentSpan.setAttribute("transfer.to_account_id", toAccountId);
        currentSpan.setAttribute("transfer.amount", amount.toString());
        currentSpan.setAttribute("transfer.simulate_failure", simulateFailureAfterDebit);

        log.info("Demonstrating mid-transaction failure: from={}, to={}, amount={}, simulateFailure={}",
                fromAccountId, toAccountId, amount, simulateFailureAfterDebit);

        // Find accounts
        Account fromAccount = accountRepository.findById(fromAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Source account not found"));
        Account toAccount = accountRepository.findById(toAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Destination account not found"));

        // Create transaction
        Transaction txn = Transaction.builder()
            .fromAccount(fromAccount)
            .toAccount(toAccount)
            .amount(amount)
            .transactionType(Transaction.TransactionType.TRANSFER)
            .status(Transaction.TransactionStatus.PENDING)
            .correlationId(UUID.fromString(correlationId != null ? correlationId : UUID.randomUUID().toString()))
            .build();
        transactionRepository.save(txn);

        // Debit from source
        fromAccount.debit(amount);
        accountRepository.save(fromAccount);

        log.info("Debited {} from account {}, balance now: {}",
                amount, fromAccountId, fromAccount.getBalance());

        // SIMULATE FAILURE HERE
        if (simulateFailureAfterDebit) {
            log.warn("Simulating system failure after debit - transaction will rollback!");
            txn.markRolledBack();
            transactionRepository.save(txn);
            throw new RuntimeException("Simulated system failure during transfer");
        }

        // Credit to destination (never reached if failure simulated)
        toAccount.credit(amount);
        accountRepository.save(toAccount);

        // Complete transaction
        txn.markCompleted();
        transactionRepository.save(txn);

        return TransferResult.success(txn.getCorrelationId().toString(), txn.getId(), 0L);
    }

    /**
     * Transfer result DTO.
     */
    public record TransferResult(
        boolean success,
        String correlationId,
        Long transactionId,
        long durationMs,
        String errorMessage
    ) {
        public static TransferResult success(String correlationId, Long transactionId, long durationMs) {
            return new TransferResult(true, correlationId, transactionId, durationMs, null);
        }

        public static TransferResult failure(String errorMessage) {
            return new TransferResult(false, null, null, 0L, errorMessage);
        }
    }

    /**
     * Exception thrown when account has insufficient funds.
     */
    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String message) {
            super(message);
        }
    }
}
