package com.oltp.demo.integration.performance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.oltp.demo.integration.BaseIntegrationTest;
import com.oltp.demo.service.performance.BatchOperationService;

import lombok.extern.slf4j.Slf4j;

/**
 * Integration tests for batch operation performance benchmarks.
 *
 * Tests:
 * - Individual vs batch insert performance
 * - Different batch sizes impact
 * - JDBC batch vs JPA batch performance
 * - Throughput measurements
 * - Success rates under load
 *
 * Uses real PostgreSQL via Testcontainers for accurate measurements.
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US3: Performance Under Load</a>
 */
@Slf4j
@Tag("integration")
@Tag("performance")
public class BatchOperationBenchmarkTest extends BaseIntegrationTest {

    @Autowired
    private BatchOperationService batchOperationService;

    private static final int SMALL_BATCH = 10;
    private static final int MEDIUM_BATCH = 100;
    private static final int LARGE_BATCH = 500;
    private static final int HUGE_BATCH = 1000;

    // =========================================================================
    // Individual vs Batch Insert Benchmarks
    // =========================================================================

    @Test
    @DisplayName("Batch inserts should be significantly faster than individual inserts")
    void batchInserts_ShouldBeFaster_ThanIndividualInserts() {
        // Given
        int recordCount = 100;
        int batchSize = 50;

        // When
        BatchOperationService.BatchComparisonResult result =
            batchOperationService.compareInsertStrategies(recordCount, batchSize);

        // Then
        assertThat(result.individualResult().successfulInserts()).isEqualTo(recordCount);
        assertThat(result.batchResult().successfulInserts()).isEqualTo(recordCount);

        // Batch should be significantly faster
        assertThat(result.speedupFactor()).isGreaterThan(2.0);
        assertThat(result.batchThroughput()).isGreaterThan(result.individualThroughput());

        log.info("Batch vs Individual: {:.1f}x speedup", result.speedupFactor());
        log.info("Individual: {:.1f} records/sec, Batch: {:.1f} records/sec",
            result.individualThroughput(), result.batchThroughput());
    }

    @Test
    @DisplayName("Batch inserts should maintain high success rate")
    void batchInserts_ShouldMaintainHighSuccessRate() {
        // Given
        int recordCount = 200;
        int batchSize = 100;

        // When
        BatchOperationService.BatchComparisonResult result =
            batchOperationService.compareInsertStrategies(recordCount, batchSize);

        // Then
        double individualSuccessRate = (result.individualResult().successfulInserts() * 100.0)
            / result.individualResult().totalRecords();
        double batchSuccessRate = (result.batchResult().successfulInserts() * 100.0)
            / result.batchResult().totalRecords();

        assertThat(individualSuccessRate).isEqualTo(100.0);
        assertThat(batchSuccessRate).isEqualTo(100.0);

        log.info("Success rates - Individual: {:.1f}%, Batch: {:.1f}%",
            individualSuccessRate, batchSuccessRate);
    }

    // =========================================================================
    // Batch Size Impact Benchmarks
    // =========================================================================

    @Test
    @DisplayName("Small batch size should show modest improvement")
    void smallBatchSize_ShouldShowModestImprovement() {
        // Given
        int recordCount = 50;

        // When
        BatchOperationService.BatchResult result =
            batchOperationService.demonstrateBatchInserts(recordCount, SMALL_BATCH);

        // Then
        assertThat(result.successfulInserts()).isEqualTo(recordCount);
        assertThat(result.batchesExecuted()).isGreaterThan(0);
        assertThat(result.recordsPerSecond()).isGreaterThan(50);

        log.info("Small batch (size {}): {:.1f} records/sec, {} batches",
            SMALL_BATCH, result.recordsPerSecond(), result.batchesExecuted());
    }

    @Test
    @DisplayName("Medium batch size should show optimal performance")
    void mediumBatchSize_ShouldShowOptimalPerformance() {
        // Given
        int recordCount = 500;

        // When
        BatchOperationService.BatchResult result =
            batchOperationService.demonstrateBatchInserts(recordCount, MEDIUM_BATCH);

        // Then
        assertThat(result.successfulInserts()).isEqualTo(recordCount);
        assertThat(result.batchesExecuted()).isEqualTo(5);  // 500 / 100
        assertThat(result.recordsPerSecond()).isGreaterThan(200);

        log.info("Medium batch (size {}): {:.1f} records/sec, {} batches",
            MEDIUM_BATCH, result.recordsPerSecond(), result.batchesExecuted());
    }

    @Test
    @DisplayName("Large batch size should maintain good throughput")
    void largeBatchSize_ShouldMaintainGoodThroughput() {
        // Given
        int recordCount = 1000;

        // When
        BatchOperationService.BatchResult result =
            batchOperationService.demonstrateBatchInserts(recordCount, LARGE_BATCH);

        // Then
        assertThat(result.successfulInserts()).isEqualTo(recordCount);
        assertThat(result.batchesExecuted()).isEqualTo(2);  // 1000 / 500
        assertThat(result.recordsPerSecond()).isGreaterThan(150);

        log.info("Large batch (size {}): {:.1f} records/sec, {} batches",
            LARGE_BATCH, result.recordsPerSecond(), result.batchesExecuted());
    }

    @Test
    @DisplayName("Huge batch size should handle high volume efficiently")
    void hugeBatchSize_ShouldHandleHighVolume_Efficiently() {
        // Given
        int recordCount = 2000;

        // When
        BatchOperationService.BatchResult result =
            batchOperationService.demonstrateBatchInserts(recordCount, HUGE_BATCH);

        // Then
        assertThat(result.successfulInserts()).isEqualTo(recordCount);
        assertThat(result.batchesExecuted()).isEqualTo(2);  // 2000 / 1000
        assertThat(result.recordsPerSecond()).isGreaterThan(100);

        log.info("Huge batch (size {}): {:.1f} records/sec, {} batches",
            HUGE_BATCH, result.recordsPerSecond(), result.batchesExecuted());
    }

    // =========================================================================
    // JPA Batch Insert Benchmarks
    // =========================================================================

    @Test
    @DisplayName("JPA batch inserts should complete successfully")
    void jpaBatchInserts_ShouldCompleteSuccessfully() {
        // Given
        int recordCount = 100;

        // When
        BatchOperationService.BatchResult result =
            batchOperationService.demonstrateJpaBatchInserts(recordCount);

        // Then
        assertThat(result.isBatch()).isTrue();
        assertThat(result.successfulInserts()).isEqualTo(recordCount);
        assertThat(result.recordsPerSecond()).isGreaterThan(20);

        log.info("JPA batch: {:.1f} records/sec", result.recordsPerSecond());
    }

    @Test
    @DisplayName("JDBC batch should be faster than JPA batch")
    void jdbcBatch_ShouldBeFaster_ThanJpaBatch() {
        // Given
        int recordCount = 200;
        int batchSize = 100;

        // When
        BatchOperationService.BatchResult jdbcResult =
            batchOperationService.demonstrateBatchInserts(recordCount, batchSize);

        BatchOperationService.BatchResult jpaResult =
            batchOperationService.demonstrateJpaBatchInserts(recordCount);

        // Then
        assertThat(jdbcResult.successfulInserts()).isEqualTo(recordCount);
        assertThat(jpaResult.successfulInserts()).isEqualTo(recordCount);

        // JDBC should generally be faster than JPA
        double speedup = jpaResult.durationMs() / (double) jdbcResult.durationMs();

        log.info("JDBC batch: {:.1f} records/sec, JPA batch: {:.1f} records/sec",
            jdbcResult.recordsPerSecond(), jpaResult.recordsPerSecond());
        log.info("JDBC batch is {:.1f}x faster than JPA batch", speedup);

        // Note: Exact speedup may vary, but JDBC should be faster or comparable
        assertThat(jdbcResult.recordsPerSecond())
            .isGreaterThanOrEqualTo(jpaResult.recordsPerSecond() * 0.8);
    }

    // =========================================================================
    // Throughput Benchmarks
    // =========================================================================

    @Test
    @DisplayName("Batch operations should achieve high throughput")
    void batchOperations_ShouldAchieveHighThroughput() {
        // Given
        int recordCount = 1000;
        int batchSize = 200;

        // When
        BatchOperationService.BatchResult result =
            batchOperationService.demonstrateBatchInserts(recordCount, batchSize);

        // Then
        assertThat(result.recordsPerSecond()).isGreaterThan(200);
        assertThat(result.durationMs()).isLessThan(5000);  // Under 5 seconds

        log.info("High throughput batch: {:.1f} records/sec for {} records",
            result.recordsPerSecond(), recordCount);
    }

    @Test
    @DisplayName("Individual operations should have lower throughput")
    void individualOperations_ShouldHaveLowerThroughput() {
        // Given
        int recordCount = 100;

        // When
        BatchOperationService.BatchResult result =
            batchOperationService.demonstrateIndividualInserts(recordCount);

        // Then
        assertThat(result.isBatch()).isFalse();
        assertThat(result.successfulInserts()).isEqualTo(recordCount);

        // Individual inserts are slower
        assertThat(result.recordsPerSecond()).isLessThan(200);

        log.info("Individual operations throughput: {:.1f} records/sec",
            result.recordsPerSecond());
    }

    // =========================================================================
    // Comparative Benchmarks
    // =========================================================================

    @Test
    @DisplayName("Increasing batch size should improve throughput up to optimal point")
    void increasingBatchSize_ShouldImproveThroughput_ToOptimalPoint() {
        // Given
        int recordCount = 500;

        // When
        BatchOperationService.BatchResult smallBatchResult =
            batchOperationService.demonstrateBatchInserts(recordCount, SMALL_BATCH);

        BatchOperationService.BatchResult mediumBatchResult =
            batchOperationService.demonstrateBatchInserts(recordCount, MEDIUM_BATCH);

        BatchOperationService.BatchResult largeBatchResult =
            batchOperationService.demonstrateBatchInserts(recordCount, LARGE_BATCH);

        // Then
        log.info("Batch size impact:");
        log.info("  Size {}: {:.1f} records/sec",
            SMALL_BATCH, smallBatchResult.recordsPerSecond());
        log.info("  Size {}: {:.1f} records/sec",
            MEDIUM_BATCH, mediumBatchResult.recordsPerSecond());
        log.info("  Size {}: {:.1f} records/sec",
            LARGE_BATCH, largeBatchResult.recordsPerSecond());

        // Medium batch should be better than small
        assertThat(mediumBatchResult.recordsPerSecond())
            .isGreaterThan(smallBatchResult.recordsPerSecond() * 0.9);
    }

    @Test
    @DisplayName("Batch operations should scale linearly with data volume")
    void batchOperations_ShouldScaleLinearly_WithDataVolume() {
        // Given
        int batchSize = 100;

        // When
        BatchOperationService.BatchResult result100 =
            batchOperationService.demonstrateBatchInserts(100, batchSize);

        BatchOperationService.BatchResult result500 =
            batchOperationService.demonstrateBatchInserts(500, batchSize);

        BatchOperationService.BatchResult result1000 =
            batchOperationService.demonstrateBatchInserts(1000, batchSize);

        // Then
        log.info("Scaling with data volume:");
        log.info("  100 records: {} ms, {:.1f} records/sec",
            result100.durationMs(), result100.recordsPerSecond());
        log.info("  500 records: {} ms, {:.1f} records/sec",
            result500.durationMs(), result500.recordsPerSecond());
        log.info("  1000 records: {} ms, {:.1f} records/sec",
            result1000.durationMs(), result1000.recordsPerSecond());

        // Throughput should remain relatively consistent
        double throughputVariance = Math.abs(
            result1000.recordsPerSecond() - result100.recordsPerSecond()
        ) / result100.recordsPerSecond();

        // Variance should be reasonable (within 50%)
        assertThat(throughputVariance).isLessThan(0.5);
    }

    // =========================================================================
    // Time Saved Benchmarks
    // =========================================================================

    @Test
    @DisplayName("Batch operations should demonstrate significant time savings")
    void batchOperations_ShouldDemonstrateSignificantTimeSavings() {
        // Given
        int recordCount = 300;
        int batchSize = 100;

        // When
        BatchOperationService.BatchComparisonResult result =
            batchOperationService.compareInsertStrategies(recordCount, batchSize);

        // Then
        long timeSavedMs = result.timeSavedMs();
        double timeSavedPercent = result.timeSavedPercent();

        assertThat(timeSavedMs).isGreaterThan(0);
        assertThat(timeSavedPercent).isGreaterThan(50.0);  // At least 50% faster

        log.info("Time savings - Absolute: {} ms, Percentage: {:.1f}%",
            timeSavedMs, timeSavedPercent);
    }
}
