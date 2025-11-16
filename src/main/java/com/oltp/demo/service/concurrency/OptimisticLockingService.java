package com.oltp.demo.service.concurrency;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.persistence.OptimisticLockException;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oltp.demo.domain.Account;
import com.oltp.demo.repository.AccountRepository;
import com.oltp.demo.util.MetricsHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating Optimistic Locking for concurrency control.
 *
 * Optimistic locking strategy:
 * - Assumes conflicts are rare
 * - Uses @Version column to detect concurrent modifications
 * - Fails with OptimisticLockException on conflict
 * - Requires retry logic in application
 *
 * Best for:
 * - Low to medium contention scenarios
 * - Read-heavy workloads
 * - When lock wait time is unacceptable
 *
 * Trade-offs:
 * + No database locks (better concurrency)
 * + No blocking (better throughput)
 * - Requires retry logic
 * - Can have high retry rates under contention
 *
 * Implementation:
 * - JPA @Version on Account entity
 * - Automatic version increment on update
 * - Spring detects version mismatch and throws exception
 * - Exponential backoff retry strategy
 *
 * @see com.oltp.demo.domain.Account (has @Version field)
 * @see <a href="specs/001-oltp-core-demo/spec.md">US2: Concurrency and Conflict Handling</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimisticLockingService {

    private final AccountRepository accountRepository;
    private final MetricsHelper metricsHelper;

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int BASE_BACKOFF_MS = 10;

    /**
     * Demonstrates concurrent updates with optimistic locking.
     *
     * Launches multiple concurrent threads that all try to update the same account.
     * JPA's @Version mechanism detects conflicts and throws OptimisticLockException.
     * Each operation retries with exponential backoff on conflict.
     *
     * Metrics tracked:
     * - Total operations
     * - Successful operations
     * - Failed operations (after max retries)
     * - Total retries
     * - Optimistic lock exceptions
     * - Average retries per operation
     *
     * @param accountId account to update concurrently
     * @param amount amount to add per operation
     * @param concurrentOperations number of concurrent operations
     * @return result with success/failure statistics
     */
    public OptimisticLockingResult executeConcurrentUpdates(
            Long accountId,
            BigDecimal amount,
            int concurrentOperations) {

        log.info("Starting optimistic locking demo: account={}, amount={}, operations={}",
                accountId, amount, concurrentOperations);

        // Get initial state
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        BigDecimal initialBalance = account.getBalance();
        Long initialVersion = account.getVersion();

        log.info("Initial state: balance={}, version={}", initialBalance, initialVersion);

        // Tracking counters
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger totalRetries = new AtomicInteger(0);
        AtomicInteger totalOptimisticLockExceptions = new AtomicInteger(0);

        // Create executor
        ExecutorService executor = Executors.newFixedThreadPool(
            Math.min(concurrentOperations, 20)  // Max 20 threads
        );

        List<CompletableFuture<UpdateOutcome>> futures = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        // Launch concurrent updates
        for (int i = 0; i < concurrentOperations; i++) {
            final int operationId = i;
            CompletableFuture<UpdateOutcome> future = CompletableFuture.supplyAsync(() ->
                updateAccountWithRetry(accountId, amount, operationId, totalOptimisticLockExceptions),
                executor
            );
            futures.add(future);
        }

        // Wait for all operations to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long duration = System.currentTimeMillis() - startTime;

        // Collect results
        for (CompletableFuture<UpdateOutcome> future : futures) {
            try {
                UpdateOutcome outcome = future.get();
                if (outcome.success) {
                    successCount.incrementAndGet();
                } else {
                    failureCount.incrementAndGet();
                }
                totalRetries.addAndGet(outcome.retryCount);
            } catch (Exception e) {
                log.error("Failed to get future result: {}", e.getMessage());
                failureCount.incrementAndGet();
            }
        }

        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify final state
        account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        BigDecimal finalBalance = account.getBalance();
        Long finalVersion = account.getVersion();

        BigDecimal expectedBalance = initialBalance.add(
            amount.multiply(BigDecimal.valueOf(successCount.get()))
        );
        boolean balanceCorrect = finalBalance.compareTo(expectedBalance) == 0;

        // Calculate statistics
        double avgRetriesPerOperation = concurrentOperations > 0 ?
            (double) totalRetries.get() / concurrentOperations : 0;
        double conflictRate = concurrentOperations > 0 ?
            (double) totalOptimisticLockExceptions.get() / concurrentOperations * 100 : 0;
        long versionIncrements = finalVersion - initialVersion;

        log.info("Optimistic locking demo completed:");
        log.info("- Success: {}/{}", successCount.get(), concurrentOperations);
        log.info("- Failures: {}", failureCount.get());
        log.info("- Total retries: {}", totalRetries.get());
        log.info("- Avg retries per operation: {:.2f}", avgRetriesPerOperation);
        log.info("- Optimistic lock exceptions: {}", totalOptimisticLockExceptions.get());
        log.info("- Conflict rate: {:.1f}%", conflictRate);
        log.info("- Version: {} → {} (incremented {} times)", initialVersion, finalVersion, versionIncrements);
        log.info("- Balance: {} → {} (expected: {})", initialBalance, finalBalance, expectedBalance);
        log.info("- Balance correct: {}", balanceCorrect);
        log.info("- Duration: {}ms", duration);

        return new OptimisticLockingResult(
            concurrentOperations,
            successCount.get(),
            failureCount.get(),
            totalRetries.get(),
            avgRetriesPerOperation,
            totalOptimisticLockExceptions.get(),
            conflictRate,
            initialBalance,
            finalBalance,
            expectedBalance,
            balanceCorrect,
            initialVersion,
            finalVersion,
            versionIncrements,
            duration
        );
    }

    /**
     * Updates account with retry logic on optimistic lock failure.
     *
     * Retry strategy:
     * - Max retries: 3
     * - Exponential backoff: 10ms, 20ms, 40ms
     * - Random jitter to avoid thundering herd
     *
     * @param accountId account to update
     * @param amount amount to add
     * @param operationId operation identifier for logging
     * @param totalExceptions counter for total optimistic lock exceptions
     * @return outcome with success status and retry count
     */
    @Transactional
    protected UpdateOutcome updateAccountWithRetry(
            Long accountId,
            BigDecimal amount,
            int operationId,
            AtomicInteger totalExceptions) {

        int retryCount = 0;
        long threadId = Thread.currentThread().getId();

        while (retryCount <= DEFAULT_MAX_RETRIES) {
            try {
                // Load account (optimistic locking enabled via @Version)
                Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found"));

                Long versionBeforeUpdate = account.getVersion();

                // Update balance
                account.credit(amount);

                // Save (JPA will check version and increment it)
                accountRepository.saveAndFlush(account);

                log.debug("Operation {} (thread {}) succeeded on attempt {} (version {} → {})",
                        operationId, threadId, retryCount + 1, versionBeforeUpdate, account.getVersion());

                return new UpdateOutcome(true, retryCount);

            } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
                totalExceptions.incrementAndGet();
                metricsHelper.recordOptimisticLockException();

                retryCount++;
                log.debug("Operation {} (thread {}) - optimistic lock conflict (attempt {})",
                        operationId, threadId, retryCount);

                if (retryCount > DEFAULT_MAX_RETRIES) {
                    log.warn("Operation {} (thread {}) exhausted retries", operationId, threadId);
                    return new UpdateOutcome(false, retryCount);
                }

                // Exponential backoff with jitter
                int backoffMs = BASE_BACKOFF_MS * (1 << (retryCount - 1)); // 10, 20, 40ms
                int jitter = (int) (Math.random() * backoffMs / 2);  // Add up to 50% jitter
                int sleepMs = backoffMs + jitter;

                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Operation {} interrupted during backoff", operationId);
                    return new UpdateOutcome(false, retryCount);
                }
            } catch (Exception e) {
                log.error("Operation {} unexpected error: {}", operationId, e.getMessage());
                return new UpdateOutcome(false, retryCount);
            }
        }

        return new UpdateOutcome(false, retryCount);
    }

    /**
     * Demonstrates optimistic locking with version conflict detection.
     *
     * Simpler version for API demonstration - single update with retry.
     *
     * @param accountId account to update
     * @param amount amount to add
     * @return result with version information
     */
    @Transactional
    public VersionedUpdateResult updateWithVersionCheck(Long accountId, BigDecimal amount) {
        log.info("Updating account {} with optimistic locking", accountId);

        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        Long versionBefore = account.getVersion();
        BigDecimal balanceBefore = account.getBalance();

        account.credit(amount);
        accountRepository.saveAndFlush(account);

        Long versionAfter = account.getVersion();
        BigDecimal balanceAfter = account.getBalance();

        log.info("Update successful: version {} → {}, balance {} → {}",
                versionBefore, versionAfter, balanceBefore, balanceAfter);

        return new VersionedUpdateResult(
            true,
            versionBefore,
            versionAfter,
            balanceBefore,
            balanceAfter,
            "Update successful with version increment"
        );
    }

    /**
     * Optimistic locking result DTO.
     */
    public record OptimisticLockingResult(
        int totalOperations,
        int successfulOperations,
        int failedOperations,
        int totalRetries,
        double avgRetriesPerOperation,
        int optimisticLockExceptions,
        double conflictRate,
        BigDecimal initialBalance,
        BigDecimal finalBalance,
        BigDecimal expectedBalance,
        boolean balanceCorrect,
        Long initialVersion,
        Long finalVersion,
        long versionIncrements,
        long durationMs
    ) {}

    /**
     * Versioned update result DTO.
     */
    public record VersionedUpdateResult(
        boolean success,
        Long versionBefore,
        Long versionAfter,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        String message
    ) {}

    /**
     * Update outcome for single operation.
     */
    private record UpdateOutcome(boolean success, int retryCount) {}
}
