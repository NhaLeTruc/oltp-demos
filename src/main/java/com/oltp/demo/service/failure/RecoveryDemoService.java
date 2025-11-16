package com.oltp.demo.service.failure;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oltp.demo.domain.Transaction;
import com.oltp.demo.domain.TransferLog;
import com.oltp.demo.repository.TransactionRepository;
import com.oltp.demo.repository.TransferLogRepository;
import com.oltp.demo.util.CorrelationIdFilter;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating crash recovery and durability verification.
 *
 * Demonstrations:
 * 1. Committed transaction verification after crash
 * 2. WAL (Write-Ahead Logging) verification
 * 3. Point-in-time recovery queries
 * 4. Transaction log analysis
 *
 * Crash Recovery Concepts:
 * - Durability: Committed transactions survive crashes
 * - WAL: All changes logged before commit
 * - REDO: Replay committed transactions after crash
 * - UNDO: Roll back uncommitted transactions after crash
 *
 * PostgreSQL Recovery:
 * - pg_wal/ directory contains Write-Ahead Log files
 * - Checkpoint: Flush dirty pages to disk
 * - Recovery: Replay WAL from last checkpoint
 * - Point-in-time recovery: Restore to specific timestamp
 *
 * Verification Methods:
 * 1. Check committed transactions exist after restart
 * 2. Verify uncommitted transactions were rolled back
 * 3. Confirm data consistency (no partial updates)
 * 4. Query transaction logs for audit trail
 *
 * Constitution.md alignment:
 * - Principle I: Data Integrity First - Durability guarantees
 * - Principle III: Resilience & Recovery - Survive crashes gracefully
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US5: Failure Scenarios and Recovery</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryDemoService {

    private final TransactionRepository transactionRepository;
    private final TransferLogRepository transferLogRepository;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Verifies committed transactions after database crash/restart.
     *
     * This method demonstrates ACID Durability by:
     * 1. Looking up transactions by correlation ID
     * 2. Verifying they exist in the database
     * 3. Confirming all related audit logs are present
     * 4. Checking data consistency
     *
     * Expected behavior after crash:
     * - All committed transactions (status='SUCCESS') should exist
     * - All uncommitted transactions should be rolled back (not visible)
     * - No partial updates (atomicity preserved)
     *
     * @param correlationId Correlation ID of transactions to verify
     * @return Recovery verification result
     */
    @WithSpan("recovery.verify_committed")
    public RecoveryResult verifyCommittedTransactions(String correlationId) {
        log.info("Verifying committed transactions: correlationId={}", correlationId);

        Span currentSpan = Span.current();
        currentSpan.setAttribute("correlation.id", correlationId);

        // Find all transactions with this correlation ID
        List<Transaction> transactions = transactionRepository
            .findByCorrelationId(correlationId);

        // Find all transfer logs with this correlation ID
        List<TransferLog> transferLogs = transferLogRepository
            .findByCorrelationId(correlationId);

        if (transactions.isEmpty()) {
            log.warn("No transactions found for correlationId={}", correlationId);
            return RecoveryResult.builder()
                .correlationId(correlationId)
                .verified(false)
                .transactionCount(0)
                .transferLogCount(0)
                .message("No transactions found - may have been rolled back or never committed")
                .build();
        }

        // Verify all transactions are committed (status='SUCCESS')
        long committedCount = transactions.stream()
            .filter(txn -> "SUCCESS".equals(txn.getStatus()))
            .count();

        boolean allCommitted = committedCount == transactions.size();

        // Verify transfer logs match transactions
        boolean logsMatch = transferLogs.size() == transactions.size();

        log.info("Recovery verification: correlationId={}, transactions={}, committed={}, logs={}, verified={}",
            correlationId, transactions.size(), committedCount, transferLogs.size(),
            allCommitted && logsMatch);

        currentSpan.setAttribute("recovery.transactions_found", transactions.size());
        currentSpan.setAttribute("recovery.committed_count", committedCount);
        currentSpan.setAttribute("recovery.verified", allCommitted && logsMatch);

        return RecoveryResult.builder()
            .correlationId(correlationId)
            .verified(allCommitted && logsMatch)
            .transactionCount(transactions.size())
            .transferLogCount(transferLogs.size())
            .committedCount((int) committedCount)
            .message(String.format("Found %d transactions, %d committed, %d audit logs",
                transactions.size(), committedCount, transferLogs.size()))
            .transactions(transactions)
            .transferLogs(transferLogs)
            .build();
    }

    /**
     * Demonstrates Write-Ahead Logging (WAL) verification.
     *
     * PostgreSQL uses WAL to ensure durability:
     * 1. Changes are first written to WAL files (pg_wal/)
     * 2. WAL is flushed to disk before transaction commits
     * 3. After crash, WAL is replayed to recover committed transactions
     *
     * This method queries PostgreSQL system views to show:
     * - Current WAL position
     * - WAL file location
     * - Last checkpoint location
     * - Recovery info (if database was restarted)
     *
     * @return WAL verification result
     */
    @WithSpan("recovery.verify_wal")
    public WalResult verifyWalConfiguration() {
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();
        log.info("Verifying WAL configuration: correlationId={}", correlationId);

        Map<String, Object> walInfo = new HashMap<>();

        try {
            // Get current WAL position
            String currentWalLsn = jdbcTemplate.queryForObject(
                "SELECT pg_current_wal_lsn()",
                String.class
            );
            walInfo.put("currentWalLsn", currentWalLsn);

            // Get last checkpoint location
            String checkpointLsn = jdbcTemplate.queryForObject(
                "SELECT checkpoint_lsn FROM pg_control_checkpoint()",
                String.class
            );
            walInfo.put("lastCheckpointLsn", checkpointLsn);

            // Get WAL settings
            Map<String, String> walSettings = jdbcTemplate.query(
                "SELECT name, setting FROM pg_settings WHERE name LIKE 'wal_%' OR name LIKE '%checkpoint%'",
                (rs) -> {
                    Map<String, String> settings = new HashMap<>();
                    while (rs.next()) {
                        settings.put(rs.getString("name"), rs.getString("setting"));
                    }
                    return settings;
                }
            );
            walInfo.put("walSettings", walSettings);

            // Check if database was recovered (indicates restart after crash)
            Boolean inRecovery = jdbcTemplate.queryForObject(
                "SELECT pg_is_in_recovery()",
                Boolean.class
            );
            walInfo.put("inRecovery", inRecovery);

            log.info("WAL verification complete: currentLsn={}, checkpointLsn={}, inRecovery={}",
                currentWalLsn, checkpointLsn, inRecovery);

            return WalResult.builder()
                .correlationId(correlationId)
                .walEnabled(true)
                .currentWalLsn(currentWalLsn)
                .lastCheckpointLsn(checkpointLsn)
                .inRecovery(inRecovery != null && inRecovery)
                .walInfo(walInfo)
                .message("WAL is enabled and operational")
                .build();

        } catch (DataAccessException e) {
            log.error("Failed to verify WAL configuration: error={}", e.getMessage(), e);

            return WalResult.builder()
                .correlationId(correlationId)
                .walEnabled(false)
                .message("Failed to query WAL information: " + e.getMessage())
                .build();
        }
    }

    /**
     * Demonstrates point-in-time recovery query.
     *
     * Shows how to query transactions within a specific time range,
     * useful for understanding what happened before a crash or for
     * recovery planning.
     *
     * Point-in-time recovery allows:
     * - Restore database to specific timestamp
     * - Replay WAL up to that point
     * - Useful for recovering from logical errors (e.g., accidental DELETE)
     *
     * @param startTime Start of time range
     * @param endTime End of time range
     * @return Point-in-time query result
     */
    @WithSpan("recovery.point_in_time_query")
    public PointInTimeResult queryTransactionsInTimeRange(
        Instant startTime,
        Instant endTime
    ) {
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();
        log.info("Querying transactions in time range: start={}, end={}, correlationId={}",
            startTime, endTime, correlationId);

        Span currentSpan = Span.current();
        currentSpan.setAttribute("recovery.start_time", startTime.toString());
        currentSpan.setAttribute("recovery.end_time", endTime.toString());

        // Convert Instant to LocalDateTime for JPA query
        LocalDateTime start = LocalDateTime.ofInstant(startTime, ZoneId.systemDefault());
        LocalDateTime end = LocalDateTime.ofInstant(endTime, ZoneId.systemDefault());

        // Query transactions in time range
        List<Transaction> transactions = jdbcTemplate.query(
            "SELECT * FROM transactions WHERE created_at BETWEEN ? AND ? ORDER BY created_at",
            (rs, rowNum) -> {
                Transaction txn = new Transaction();
                txn.setId(rs.getLong("id"));
                txn.setFromAccountId(rs.getLong("from_account_id"));
                txn.setToAccountId(rs.getLong("to_account_id"));
                txn.setAmount(rs.getBigDecimal("amount"));
                txn.setStatus(rs.getString("status"));
                txn.setCorrelationId(rs.getString("correlation_id"));
                txn.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                return txn;
            },
            java.sql.Timestamp.from(startTime),
            java.sql.Timestamp.from(endTime)
        );

        // Calculate statistics
        long successCount = transactions.stream()
            .filter(txn -> "SUCCESS".equals(txn.getStatus()))
            .count();

        BigDecimal totalAmount = transactions.stream()
            .filter(txn -> "SUCCESS".equals(txn.getStatus()))
            .map(Transaction::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        log.info("Point-in-time query complete: found {} transactions, {} successful, total={}",
            transactions.size(), successCount, totalAmount);

        currentSpan.setAttribute("recovery.transactions_found", transactions.size());
        currentSpan.setAttribute("recovery.success_count", successCount);

        return PointInTimeResult.builder()
            .correlationId(correlationId)
            .startTime(startTime)
            .endTime(endTime)
            .transactionCount(transactions.size())
            .successCount((int) successCount)
            .totalAmount(totalAmount)
            .transactions(transactions)
            .message(String.format("Found %d transactions in time range", transactions.size()))
            .build();
    }

    /**
     * Gets database recovery statistics.
     *
     * Queries PostgreSQL statistics to show:
     * - Database uptime (time since last restart)
     * - Number of transactions committed
     * - Number of transactions rolled back
     * - Checkpoint statistics
     *
     * @return Recovery statistics
     */
    @WithSpan("recovery.statistics")
    public Map<String, Object> getRecoveryStatistics() {
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();
        log.info("Fetching recovery statistics: correlationId={}", correlationId);

        Map<String, Object> stats = new HashMap<>();

        try {
            // Get database start time (uptime since last crash/restart)
            java.sql.Timestamp startTime = jdbcTemplate.queryForObject(
                "SELECT pg_postmaster_start_time()",
                java.sql.Timestamp.class
            );
            stats.put("databaseStartTime", startTime);
            stats.put("uptimeSeconds",
                java.time.Duration.between(startTime.toInstant(), Instant.now()).getSeconds());

            // Get transaction statistics
            Map<String, Long> txnStats = jdbcTemplate.query(
                "SELECT xact_commit, xact_rollback FROM pg_stat_database WHERE datname = current_database()",
                (rs) -> {
                    Map<String, Long> result = new HashMap<>();
                    if (rs.next()) {
                        result.put("committed", rs.getLong("xact_commit"));
                        result.put("rolledBack", rs.getLong("xact_rollback"));
                    }
                    return result;
                }
            );
            stats.put("transactionStats", txnStats);

            // Get checkpoint statistics
            Map<String, Object> checkpointStats = jdbcTemplate.query(
                "SELECT checkpoints_timed, checkpoints_req, checkpoint_write_time, checkpoint_sync_time FROM pg_stat_bgwriter",
                (rs) -> {
                    Map<String, Object> result = new HashMap<>();
                    if (rs.next()) {
                        result.put("timedCheckpoints", rs.getLong("checkpoints_timed"));
                        result.put("requestedCheckpoints", rs.getLong("checkpoints_req"));
                        result.put("writeTime", rs.getDouble("checkpoint_write_time"));
                        result.put("syncTime", rs.getDouble("checkpoint_sync_time"));
                    }
                    return result;
                }
            );
            stats.put("checkpointStats", checkpointStats);

            log.info("Recovery statistics: {}", stats);
            return stats;

        } catch (DataAccessException e) {
            log.error("Failed to fetch recovery statistics: error={}", e.getMessage(), e);
            stats.put("error", e.getMessage());
            return stats;
        }
    }

    /**
     * Result object for recovery verification.
     */
    @lombok.Builder
    @lombok.Data
    public static class RecoveryResult {
        private String correlationId;
        private boolean verified;
        private int transactionCount;
        private int transferLogCount;
        private int committedCount;
        private String message;
        private List<Transaction> transactions;
        private List<TransferLog> transferLogs;
    }

    /**
     * Result object for WAL verification.
     */
    @lombok.Builder
    @lombok.Data
    public static class WalResult {
        private String correlationId;
        private boolean walEnabled;
        private String currentWalLsn;
        private String lastCheckpointLsn;
        private boolean inRecovery;
        private Map<String, Object> walInfo;
        private String message;
    }

    /**
     * Result object for point-in-time queries.
     */
    @lombok.Builder
    @lombok.Data
    public static class PointInTimeResult {
        private String correlationId;
        private Instant startTime;
        private Instant endTime;
        private int transactionCount;
        private int successCount;
        private BigDecimal totalAmount;
        private List<Transaction> transactions;
        private String message;
    }
}
