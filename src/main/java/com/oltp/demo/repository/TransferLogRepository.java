package com.oltp.demo.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.oltp.demo.domain.TransferLog;

/**
 * Repository for TransferLog entity.
 *
 * Transfer logs are append-only audit records used to demonstrate durability.
 * This table has high write throughput and is a good target for batch insert
 * demonstrations.
 *
 * Key characteristics:
 * - Append-only (no updates or deletes in normal operation)
 * - Survives application crashes and restarts
 * - Used for audit trail and compliance
 * - Good target for batch insert performance testing
 *
 * Indexes used:
 * - idx_transfer_logs_correlation_id: Fast lookup by correlation ID
 * - idx_transfer_logs_transaction_id: Link to transactions
 * - idx_transfer_logs_logged_at: Chronological ordering (DESC)
 *
 * @see com.oltp.demo.service.acid.DurabilityDemoService
 */
@Repository
public interface TransferLogRepository extends JpaRepository<TransferLog, Long> {

    /**
     * Find all logs for a specific transaction.
     *
     * Uses index: idx_transfer_logs_transaction_id
     *
     * @param transactionId the transaction ID
     * @return list of logs for the transaction
     */
    List<TransferLog> findByTransactionId(Long transactionId);

    /**
     * Find all logs with a specific correlation ID.
     *
     * Used for distributed tracing - correlate logs across the system.
     *
     * Uses index: idx_transfer_logs_correlation_id
     *
     * @param correlationId the correlation UUID
     * @return list of logs with the correlation ID
     */
    List<TransferLog> findByCorrelationId(UUID correlationId);

    /**
     * Find logs by status.
     *
     * Used to analyze transfer outcomes (INITIATED vs COMPLETED vs FAILED).
     *
     * @param status the transfer status
     * @return list of logs with the specified status
     */
    List<TransferLog> findByStatus(TransferLog.TransferStatus status);

    /**
     * Find logs involving a specific account (from or to).
     *
     * Uses indexes: idx_transfer_logs_from_account, idx_transfer_logs_to_account
     *
     * @param accountId the account ID
     * @return list of logs involving the account
     */
    @Query("SELECT tl FROM TransferLog tl WHERE tl.fromAccount.id = :accountId OR tl.toAccount.id = :accountId")
    List<TransferLog> findByAccountId(@Param("accountId") Long accountId);

    /**
     * Find recent logs (last N days) ordered by logged time.
     *
     * Uses index: idx_transfer_logs_logged_at (DESC order)
     *
     * @param since the cutoff timestamp
     * @return list of recent logs
     */
    @Query("SELECT tl FROM TransferLog tl WHERE tl.loggedAt >= :since ORDER BY tl.loggedAt DESC")
    List<TransferLog> findRecentLogs(@Param("since") Instant since);

    /**
     * Find completed transfer logs for durability verification.
     *
     * After a crash and restart, this query verifies that committed
     * transfers were durably persisted.
     *
     * @return list of completed transfer logs
     */
    @Query("SELECT tl FROM TransferLog tl WHERE tl.status = 'COMPLETED' ORDER BY tl.loggedAt DESC")
    List<TransferLog> findCompletedLogs();

    /**
     * Find failed transfer logs for error analysis.
     *
     * @return list of failed transfer logs
     */
    @Query("SELECT tl FROM TransferLog tl WHERE tl.status = 'FAILED' ORDER BY tl.loggedAt DESC")
    List<TransferLog> findFailedLogs();

    /**
     * Count logs by status.
     *
     * More efficient than loading all logs and counting in memory.
     *
     * @param status the transfer status
     * @return count of logs with the status
     */
    long countByStatus(TransferLog.TransferStatus status);

    /**
     * Find logs within a time range (for reporting).
     *
     * @param start start of time range
     * @param end end of time range
     * @return list of logs within the time range
     */
    @Query("SELECT tl FROM TransferLog tl WHERE tl.loggedAt BETWEEN :start AND :end ORDER BY tl.loggedAt")
    List<TransferLog> findByLoggedAtBetween(@Param("start") Instant start, @Param("end") Instant end);
}
