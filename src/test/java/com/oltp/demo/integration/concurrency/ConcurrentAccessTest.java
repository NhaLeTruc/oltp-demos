package com.oltp.demo.integration.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.oltp.demo.domain.Account;
import com.oltp.demo.integration.BaseIntegrationTest;
import com.oltp.demo.repository.AccountRepository;
import com.oltp.demo.service.concurrency.OptimisticLockingService;
import com.oltp.demo.service.concurrency.PessimisticLockingService;

/**
 * High-concurrency integration tests with 100+ parallel clients.
 *
 * Tests verify that:
 * - System handles high concurrent load
 * - Optimistic locking succeeds with retries under contention
 * - Pessimistic locking serializes access correctly
 * - Final balance is correct after all concurrent operations
 * - System throughput is measurable
 *
 * Uses ExecutorService to simulate realistic concurrent access patterns.
 */
@SpringBootTest
class ConcurrentAccessTest extends BaseIntegrationTest {

    @Autowired
    private OptimisticLockingService optimisticLockingService;

    @Autowired
    private PessimisticLockingService pessimisticLockingService;

    @Autowired
    private AccountRepository accountRepository;

    private static final int HIGH_CONCURRENCY = 100;
    private static final int VERY_HIGH_CONCURRENCY = 200;

    @Test
    void highConcurrency_OptimisticLocking_ShouldEventuallySucceed() {
        // Given
        Long accountId = 1L;
        BigDecimal amount = BigDecimal.valueOf(1.00);
        BigDecimal initialBalance = accountRepository.findById(accountId)
            .map(Account::getBalance)
            .orElseThrow(() -> new IllegalStateException("Test account not found"));

        // When - 100 concurrent operations with optimistic locking
        long startTime = System.currentTimeMillis();

        OptimisticLockingService.OptimisticLockingResult result =
            optimisticLockingService.executeConcurrentUpdates(accountId, amount, HIGH_CONCURRENCY);

        long durationMs = System.currentTimeMillis() - startTime;

        // Then
        assertThat(result.totalOperations()).isEqualTo(HIGH_CONCURRENCY);
        assertThat(result.successfulOperations())
            .isGreaterThanOrEqualTo((int) (HIGH_CONCURRENCY * 0.9))  // At least 90% success
            .withFailMessage("Most operations should eventually succeed despite retries");

        assertThat(result.balanceCorrect()).isTrue()
            .withFailMessage("Final balance must be consistent");

        // With high concurrency, expect conflicts and retries
        assertThat(result.optimisticLockExceptions()).isGreaterThan(0);
        assertThat(result.totalRetries()).isGreaterThan(0);

        // Log performance metrics
        System.out.printf("""
            High Concurrency Optimistic Locking (100 clients):
            - Successful operations: %d / %d (%.1f%%)
            - Failed operations: %d
            - Total retries: %d
            - Avg retries per operation: %.2f
            - Optimistic lock exceptions: %d
            - Conflict rate: %.1f%%
            - Duration: %dms
            - Throughput: %.1f ops/sec
            %n""",
            result.successfulOperations(),
            result.totalOperations(),
            (result.successfulOperations() * 100.0 / result.totalOperations()),
            result.failedOperations(),
            result.totalRetries(),
            result.avgRetriesPerOperation(),
            result.optimisticLockExceptions(),
            result.conflictRate(),
            durationMs,
            (HIGH_CONCURRENCY * 1000.0 / durationMs)
        );
    }

    @Test
    void highConcurrency_PessimisticLocking_ShouldAllSucceed() {
        // Given
        Long accountId = 2L;
        BigDecimal amount = BigDecimal.valueOf(0.50);

        // When - 100 concurrent operations with pessimistic locking
        long startTime = System.currentTimeMillis();

        PessimisticLockingService.PessimisticLockingResult result =
            pessimisticLockingService.executeConcurrentUpdates(accountId, amount, HIGH_CONCURRENCY);

        long durationMs = System.currentTimeMillis() - startTime;

        // Then - pessimistic locking should have 100% success (no retries needed)
        assertThat(result.totalOperations()).isEqualTo(HIGH_CONCURRENCY);
        assertThat(result.successfulOperations()).isEqualTo(HIGH_CONCURRENCY)
            .withFailMessage("Pessimistic locking should succeed for all operations");
        assertThat(result.failedOperations()).isEqualTo(0);

        assertThat(result.balanceCorrect()).isTrue()
            .withFailMessage("Final balance must be consistent");

        // Verify lock wait times
        assertThat(result.totalLockWaitMs()).isGreaterThan(0)
            .withFailMessage("Some lock contention should occur");
        assertThat(result.avgLockWaitMs()).isGreaterThan(0);

        // Log performance metrics
        System.out.printf("""
            High Concurrency Pessimistic Locking (100 clients):
            - Successful operations: %d / %d (100%%)
            - Failed operations: %d
            - Total lock wait time: %dms
            - Avg lock wait time: %.2fms
            - Max lock wait time: %dms
            - Duration: %dms
            - Throughput: %.1f ops/sec
            %n""",
            result.successfulOperations(),
            result.totalOperations(),
            result.failedOperations(),
            result.totalLockWaitMs(),
            result.avgLockWaitMs(),
            result.maxLockWaitMs(),
            durationMs,
            (HIGH_CONCURRENCY * 1000.0 / durationMs)
        );
    }

    @Test
    void veryHighConcurrency_OptimisticLocking_ShouldMaintainCorrectness() {
        // Given
        Long accountId = 3L;
        BigDecimal amount = BigDecimal.valueOf(0.25);

        // When - 200 concurrent operations (extreme contention)
        OptimisticLockingService.OptimisticLockingResult result =
            optimisticLockingService.executeConcurrentUpdates(accountId, amount, VERY_HIGH_CONCURRENCY);

        // Then - despite extreme contention, correctness must be maintained
        assertThat(result.balanceCorrect()).isTrue()
            .withFailMessage("Balance correctness must be maintained even under extreme load");

        assertThat(result.successfulOperations())
            .isGreaterThanOrEqualTo((int) (VERY_HIGH_CONCURRENCY * 0.7))  // At least 70% success
            .withFailMessage("Most operations should eventually succeed");

        // With very high concurrency, expect high conflict rates
        assertThat(result.conflictRate()).isGreaterThan(0);

        System.out.printf("""
            Very High Concurrency Optimistic Locking (200 clients):
            - Success rate: %.1f%%
            - Conflict rate: %.1f%%
            - Balance correct: %s
            %n""",
            (result.successfulOperations() * 100.0 / result.totalOperations()),
            result.conflictRate(),
            result.balanceCorrect()
        );
    }

    @Test
    void mixedConcurrency_BothStrategies_ShouldComparePerformance() {
        // Given
        Long optimisticAccountId = 4L;
        Long pessimisticAccountId = 5L;
        BigDecimal amount = BigDecimal.valueOf(0.10);
        int operations = 50;

        // When - run both strategies in parallel
        long startTime = System.currentTimeMillis();

        CompletableFuture<OptimisticLockingService.OptimisticLockingResult> optimisticFuture =
            CompletableFuture.supplyAsync(() ->
                optimisticLockingService.executeConcurrentUpdates(optimisticAccountId, amount, operations)
            );

        CompletableFuture<PessimisticLockingService.PessimisticLockingResult> pessimisticFuture =
            CompletableFuture.supplyAsync(() ->
                pessimisticLockingService.executeConcurrentUpdates(pessimisticAccountId, amount, operations)
            );

        CompletableFuture.allOf(optimisticFuture, pessimisticFuture).join();

        long totalDurationMs = System.currentTimeMillis() - startTime;

        OptimisticLockingService.OptimisticLockingResult optimisticResult = optimisticFuture.join();
        PessimisticLockingService.PessimisticLockingResult pessimisticResult = pessimisticFuture.join();

        // Then - both should be correct
        assertThat(optimisticResult.balanceCorrect()).isTrue();
        assertThat(pessimisticResult.balanceCorrect()).isTrue();

        // Compare performance characteristics
        System.out.printf("""
            Mixed Concurrency Performance Comparison:

            Optimistic Locking:
            - Success rate: %.1f%%
            - Conflict rate: %.1f%%
            - Avg retries: %.2f
            - Duration: %dms

            Pessimistic Locking:
            - Success rate: 100%%
            - Avg lock wait: %.2fms
            - Max lock wait: %dms
            - Duration: %dms

            Total duration (parallel): %dms
            %n""",
            (optimisticResult.successfulOperations() * 100.0 / operations),
            optimisticResult.conflictRate(),
            optimisticResult.avgRetriesPerOperation(),
            optimisticResult.durationMs(),
            pessimisticResult.avgLockWaitMs(),
            pessimisticResult.maxLockWaitMs(),
            pessimisticResult.durationMs(),
            totalDurationMs
        );
    }

    @Test
    void stressTest_ContinuousLoad_ShouldMaintainCorrectness() {
        // Given
        Long accountId = 6L;
        BigDecimal amount = BigDecimal.valueOf(0.05);
        int iterations = 5;
        int operationsPerIteration = 20;

        AtomicInteger totalSuccess = new AtomicInteger(0);
        AtomicInteger totalFailures = new AtomicInteger(0);
        AtomicInteger totalConflicts = new AtomicInteger(0);

        // When - run multiple iterations of concurrent operations
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < iterations; i++) {
            OptimisticLockingService.OptimisticLockingResult result =
                optimisticLockingService.executeConcurrentUpdates(
                    accountId,
                    amount,
                    operationsPerIteration
                );

            totalSuccess.addAndGet(result.successfulOperations());
            totalFailures.addAndGet(result.failedOperations());
            totalConflicts.addAndGet(result.optimisticLockExceptions());

            // Verify correctness after each iteration
            assertThat(result.balanceCorrect()).isTrue()
                .withFailMessage("Balance must be correct after iteration " + (i + 1));
        }

        long totalDurationMs = System.currentTimeMillis() - startTime;

        // Then
        int totalOperations = iterations * operationsPerIteration;
        assertThat(totalSuccess.get() + totalFailures.get()).isEqualTo(totalOperations);

        System.out.printf("""
            Stress Test Results (%d iterations × %d operations):
            - Total successful: %d / %d (%.1f%%)
            - Total failures: %d
            - Total conflicts: %d
            - Total duration: %dms
            - Avg ops/sec: %.1f
            %n""",
            iterations,
            operationsPerIteration,
            totalSuccess.get(),
            totalOperations,
            (totalSuccess.get() * 100.0 / totalOperations),
            totalFailures.get(),
            totalConflicts.get(),
            totalDurationMs,
            (totalOperations * 1000.0 / totalDurationMs)
        );
    }
}
