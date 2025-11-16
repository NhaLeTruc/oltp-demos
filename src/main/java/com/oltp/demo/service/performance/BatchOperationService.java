package com.oltp.demo.service.performance;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oltp.demo.domain.TransferLog;
import com.oltp.demo.repository.TransferLogRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating batch operation performance benefits.
 *
 * Compares:
 * - Individual inserts - one database round-trip per insert
 * - JDBC batch inserts - multiple inserts in single round-trip
 *
 * Expected results:
 * - 20x faster throughput with batch operations
 * - Reduced network overhead
 * - Better database efficiency
 *
 * Uses transfer_logs table for demonstrations to avoid FK constraints.
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US3: Performance Under Load</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchOperationService {

    private final DataSource dataSource;
    private final TransferLogRepository transferLogRepository;

    private static final String INSERT_TRANSFER_LOG_SQL =
        "INSERT INTO transfer_logs (correlation_id, account_id, event_type, event_time, details, created_at) " +
        "VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)";

    /**
     * Demonstrates individual insert operations.
     *
     * Executes one INSERT statement per record.
     * Each insert requires a separate database round-trip.
     *
     * @param recordCount Number of records to insert
     * @return Performance results
     */
    @Transactional
    public BatchResult demonstrateIndividualInserts(int recordCount) {
        long startTime = System.currentTimeMillis();
        int successfulInserts = 0;
        UUID correlationId = UUID.randomUUID();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_TRANSFER_LOG_SQL)) {

            for (int i = 0; i < recordCount; i++) {
                ps.setObject(1, correlationId);
                ps.setLong(2, (i % 10) + 1);  // Account IDs 1-10
                ps.setString(3, "PERF_TEST_INDIVIDUAL_" + i);
                ps.setString(4, "Individual insert test record " + i);

                int rowsAffected = ps.executeUpdate();
                if (rowsAffected > 0) {
                    successfulInserts++;
                }
            }

        } catch (SQLException e) {
            log.error("Individual inserts failed", e);
        }

        long durationMs = System.currentTimeMillis() - startTime;

        return new BatchResult(
            false,  // isBatch
            recordCount,
            successfulInserts,
            0,  // No batches for individual inserts
            durationMs,
            correlationId
        );
    }

    /**
     * Demonstrates JDBC batch insert operations.
     *
     * Groups multiple inserts into batches and executes in single round-trip.
     * Dramatically reduces network overhead and improves throughput.
     *
     * @param recordCount Number of records to insert
     * @param batchSize Size of each batch (typically 100-1000)
     * @return Performance results
     */
    @Transactional
    public BatchResult demonstrateBatchInserts(int recordCount, int batchSize) {
        long startTime = System.currentTimeMillis();
        int successfulInserts = 0;
        int batchesExecuted = 0;
        UUID correlationId = UUID.randomUUID();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_TRANSFER_LOG_SQL)) {

            for (int i = 0; i < recordCount; i++) {
                ps.setObject(1, correlationId);
                ps.setLong(2, (i % 10) + 1);
                ps.setString(3, "PERF_TEST_BATCH_" + i);
                ps.setString(4, "Batch insert test record " + i);

                ps.addBatch();

                // Execute batch when batch size reached or at end
                if ((i + 1) % batchSize == 0 || i == recordCount - 1) {
                    int[] batchResults = ps.executeBatch();
                    batchesExecuted++;

                    for (int result : batchResults) {
                        if (result > 0 || result == PreparedStatement.SUCCESS_NO_INFO) {
                            successfulInserts++;
                        }
                    }
                }
            }

        } catch (SQLException e) {
            log.error("Batch inserts failed", e);
        }

        long durationMs = System.currentTimeMillis() - startTime;

        return new BatchResult(
            true,  // isBatch
            recordCount,
            successfulInserts,
            batchesExecuted,
            durationMs,
            correlationId
        );
    }

    /**
     * Demonstrates JPA batch inserts using Spring Data.
     *
     * Uses JPA's batch processing capabilities configured in application.yml.
     * Typically slower than pure JDBC but provides entity management benefits.
     *
     * @param recordCount Number of records to insert
     * @return Performance results
     */
    @Transactional
    public BatchResult demonstrateJpaBatchInserts(int recordCount) {
        long startTime = System.currentTimeMillis();
        UUID correlationId = UUID.randomUUID();

        List<TransferLog> logs = new ArrayList<>();

        for (int i = 0; i < recordCount; i++) {
            TransferLog log = TransferLog.builder()
                .correlationId(correlationId)
                .accountId((long) ((i % 10) + 1))
                .eventType("PERF_TEST_JPA_" + i)
                .details("JPA batch insert test record " + i)
                .build();

            logs.add(log);
        }

        // saveAll uses JPA batch processing
        List<TransferLog> saved = transferLogRepository.saveAll(logs);

        long durationMs = System.currentTimeMillis() - startTime;

        return new BatchResult(
            true,  // isBatch (JPA batch)
            recordCount,
            saved.size(),
            (recordCount + 49) / 50,  // Estimate batches (default batch size = 50)
            durationMs,
            correlationId
        );
    }

    /**
     * Compares individual vs batch insert performance.
     *
     * Runs both approaches and calculates speedup factor.
     *
     * @param recordCount Number of records for each test
     * @param batchSize Batch size for batch test
     * @return Comparison results
     */
    @Transactional
    public BatchComparisonResult compareInsertStrategies(int recordCount, int batchSize) {
        // Run individual inserts
        BatchResult individualResult = demonstrateIndividualInserts(recordCount);

        // Small delay between tests
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Run batch inserts
        BatchResult batchResult = demonstrateBatchInserts(recordCount, batchSize);

        double speedup = (double) individualResult.durationMs / batchResult.durationMs;

        return new BatchComparisonResult(
            individualResult,
            batchResult,
            speedup,
            String.format("Batch operations are %.1fx faster than individual inserts", speedup)
        );
    }

    /**
     * Cleans up test data by correlation ID.
     *
     * @param correlationId Correlation ID of test data to delete
     * @return Number of records deleted
     */
    @Transactional
    public int cleanupTestData(UUID correlationId) {
        List<TransferLog> logs = transferLogRepository.findByCorrelationId(correlationId);
        int count = logs.size();
        transferLogRepository.deleteAll(logs);
        log.info("Cleaned up {} test transfer logs with correlation_id={}", count, correlationId);
        return count;
    }

    // =========================================================================
    // Result DTOs
    // =========================================================================

    public record BatchResult(
        boolean isBatch,
        int totalRecords,
        int successfulInserts,
        int batchesExecuted,
        long durationMs,
        UUID correlationId
    ) {
        public double recordsPerSecond() {
            return (successfulInserts * 1000.0) / durationMs;
        }

        public double avgRecordsPerBatch() {
            return batchesExecuted > 0 ? (double) totalRecords / batchesExecuted : 0;
        }
    }

    public record BatchComparisonResult(
        BatchResult individualResult,
        BatchResult batchResult,
        double speedupFactor,
        String summary
    ) {
        public double individualThroughput() {
            return individualResult.recordsPerSecond();
        }

        public double batchThroughput() {
            return batchResult.recordsPerSecond();
        }

        public long timeSavedMs() {
            return individualResult.durationMs - batchResult.durationMs;
        }

        public double timeSavedPercent() {
            return ((individualResult.durationMs - batchResult.durationMs) * 100.0)
                / individualResult.durationMs;
        }
    }
}
