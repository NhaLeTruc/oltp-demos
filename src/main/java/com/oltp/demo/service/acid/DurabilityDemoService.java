package com.oltp.demo.service.acid;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oltp.demo.domain.Transaction;
import com.oltp.demo.domain.TransferLog;
import com.oltp.demo.repository.TransactionRepository;
import com.oltp.demo.repository.TransferLogRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating ACID Durability property.
 *
 * Durability: Once a transaction commits, its changes are permanent.
 * Data survives system crashes, power failures, and restarts.
 *
 * Demonstrations:
 * 1. Committed transaction persistence: Verify transactions survive restart
 * 2. Write-Ahead Logging (WAL): Transfer logs as audit trail
 * 3. Crash recovery verification: Query committed transactions post-"crash"
 * 4. Correlation ID tracking: End-to-end traceability
 *
 * Key mechanisms:
 * - PostgreSQL WAL (Write-Ahead Logging)
 * - fsync=on in postgresql.conf (durability guarantee)
 * - Append-only transfer_logs table (immutable audit trail)
 * - Transaction status tracking (PENDING → COMPLETED)
 *
 * Verification strategy:
 * 1. Create transaction and mark COMPLETED
 * 2. Write to transfer_logs (append-only)
 * 3. Commit transaction
 * 4. Simulate crash (restart application)
 * 5. Verify transaction and logs still exist
 *
 * Constitution.md alignment:
 * - Principle I: Data Integrity First
 * - ACID compliance is the foundation
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US1: ACID Transaction Guarantees</a>
 * @see <a href="infrastructure/docker/postgres/postgresql.conf">PostgreSQL Config</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DurabilityDemoService {

    private final TransactionRepository transactionRepository;
    private final TransferLogRepository transferLogRepository;

    /**
     * Demonstrates that committed transactions are durable.
     *
     * Creates a transaction, marks it completed, and commits.
     * This transaction should survive any subsequent system failure.
     *
     * PostgreSQL guarantees:
     * - Transaction written to WAL before commit returns
     * - WAL fsync'd to disk before commit confirmation
     * - Data survives crash and is recovered on restart
     *
     * @param correlationId correlation ID for tracing
     * @return durability verification result
     */
    @Transactional
    public DurabilityResult demonstrateDurableCommit(UUID correlationId) {
        log.info("Demonstrating durable commit: correlationId={}", correlationId);

        Instant commitTime = Instant.now();

        // Create completed transaction (simulating a real transfer)
        Transaction txn = Transaction.builder()
            .fromAccount(null)  // Simplified demo
            .toAccount(null)
            .amount(java.math.BigDecimal.valueOf(100.00))
            .transactionType(Transaction.TransactionType.DEPOSIT)
            .status(Transaction.TransactionStatus.COMPLETED)
            .correlationId(correlationId)
            .build();
        txn.setCompletedAt(commitTime);

        txn = transactionRepository.save(txn);
        log.info("Transaction created and committed: id={}, status={}",
                txn.getId(), txn.getStatus());

        // Create audit log entry (demonstrating WAL concept)
        TransferLog log = TransferLog.builder()
            .transaction(txn)
            .fromAccount(null)  // Simplified
            .toAccount(null)
            .amount(txn.getAmount())
            .status(TransferLog.TransferStatus.COMPLETED)
            .correlationId(correlationId)
            .build();

        transferLogRepository.save(log);
        log.info("Transfer log recorded: logId={}", log.getId());

        // At this point, when @Transactional commits:
        // 1. Changes are written to PostgreSQL WAL
        // 2. WAL is fsync'd to disk
        // 3. Commit returns successfully
        // 4. Data is DURABLE - survives any subsequent crash

        return new DurabilityResult(
            true,
            correlationId,
            txn.getId(),
            log.getId(),
            commitTime,
            "Transaction committed and durably persisted to disk"
        );
    }

    /**
     * Verifies crash recovery by checking for completed transactions.
     *
     * After simulated crash/restart, this method verifies that:
     * - Completed transactions are still present
     * - Transfer logs are intact
     * - Data matches expected state
     *
     * This demonstrates that committed data survived the "crash".
     *
     * @param correlationId correlation ID to look up
     * @return recovery verification result
     */
    @Transactional(readOnly = true)
    public RecoveryVerificationResult verifyCrashRecovery(UUID correlationId) {
        log.info("Verifying crash recovery: correlationId={}", correlationId);

        // Look up transaction by correlation ID
        Transaction txn = transactionRepository.findByCorrelationId(correlationId)
            .orElse(null);

        if (txn == null) {
            log.warn("Transaction not found after crash - NOT DURABLE!");
            return new RecoveryVerificationResult(
                false,
                correlationId,
                null,
                null,
                0,
                "Transaction not found - durability violated!"
            );
        }

        // Look up transfer logs
        List<TransferLog> logs = transferLogRepository.findByCorrelationId(correlationId);

        log.info("Found transaction: id={}, status={}, completed={}",
                txn.getId(), txn.getStatus(), txn.getCompletedAt());
        log.info("Found {} transfer log(s)", logs.size());

        boolean txnCompleted = txn.getStatus() == Transaction.TransactionStatus.COMPLETED;
        boolean logsExist = !logs.isEmpty();
        boolean durable = txnCompleted && logsExist;

        return new RecoveryVerificationResult(
            durable,
            correlationId,
            txn.getId(),
            txn.getCompletedAt(),
            logs.size(),
            durable ? "Transaction and logs survived crash - DURABLE ✓" :
                     "Data incomplete after crash - durability issue!"
        );
    }

    /**
     * Retrieves all completed transactions from the last N minutes.
     *
     * Used to verify that recent completed transactions are durable.
     * Demonstrates that the database can be queried for committed
     * state after restart.
     *
     * @param minutes number of minutes to look back
     * @return list of completed transactions
     */
    @Transactional(readOnly = true)
    public List<CompletedTransactionInfo> getRecentCompletedTransactions(int minutes) {
        log.info("Retrieving completed transactions from last {} minutes", minutes);

        Instant since = Instant.now().minus(minutes, ChronoUnit.MINUTES);

        List<Transaction> transactions = transactionRepository.findRecentTransactions(since)
            .stream()
            .filter(t -> t.getStatus() == Transaction.TransactionStatus.COMPLETED)
            .collect(Collectors.toList());

        log.info("Found {} completed transactions", transactions.size());

        return transactions.stream()
            .map(t -> new CompletedTransactionInfo(
                t.getId(),
                t.getCorrelationId(),
                t.getAmount(),
                t.getCreatedAt(),
                t.getCompletedAt()
            ))
            .collect(Collectors.toList());
    }

    /**
     * Retrieves transfer log audit trail by correlation ID.
     *
     * Demonstrates immutable audit trail:
     * - Logs are append-only (no updates/deletes)
     * - Complete history preserved
     * - Survives crashes
     *
     * @param correlationId correlation ID to trace
     * @return list of transfer log entries
     */
    @Transactional(readOnly = true)
    public List<TransferLogInfo> getTransferAuditTrail(UUID correlationId) {
        log.info("Retrieving transfer audit trail: correlationId={}", correlationId);

        List<TransferLog> logs = transferLogRepository.findByCorrelationId(correlationId);

        log.info("Found {} log entries", logs.size());

        return logs.stream()
            .map(l -> new TransferLogInfo(
                l.getId(),
                l.getCorrelationId(),
                l.getStatus(),
                l.getAmount(),
                l.getLoggedAt()
            ))
            .collect(Collectors.toList());
    }

    /**
     * Counts completed transactions in the database.
     *
     * Simple verification that committed data persists.
     *
     * @return count of completed transactions
     */
    @Transactional(readOnly = true)
    public long countCompletedTransactions() {
        long count = transactionRepository.countByStatus(Transaction.TransactionStatus.COMPLETED);
        log.info("Total completed transactions: {}", count);
        return count;
    }

    /**
     * Durability result DTO.
     */
    public record DurabilityResult(
        boolean committed,
        UUID correlationId,
        Long transactionId,
        Long transferLogId,
        Instant commitTime,
        String message
    ) {}

    /**
     * Recovery verification result DTO.
     */
    public record RecoveryVerificationResult(
        boolean dataRecovered,
        UUID correlationId,
        Long transactionId,
        Instant completedAt,
        int logCount,
        String message
    ) {}

    /**
     * Completed transaction info DTO.
     */
    public record CompletedTransactionInfo(
        Long id,
        UUID correlationId,
        java.math.BigDecimal amount,
        Instant createdAt,
        Instant completedAt
    ) {}

    /**
     * Transfer log info DTO.
     */
    public record TransferLogInfo(
        Long id,
        UUID correlationId,
        TransferLog.TransferStatus status,
        java.math.BigDecimal amount,
        Instant loggedAt
    ) {}
}
