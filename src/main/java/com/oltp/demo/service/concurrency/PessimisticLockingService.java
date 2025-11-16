package com.oltp.demo.service.concurrency;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oltp.demo.domain.Account;
import com.oltp.demo.repository.AccountRepository;
import com.oltp.demo.util.MetricsHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating Pessimistic Locking for concurrency control.
 *
 * Pessimistic locking strategy:
 * - Assumes conflicts are common
 * - Acquires database lock before modifying data
 * - Uses SELECT FOR UPDATE (exclusive row lock)
 * - Blocks other transactions until lock is released
 *
 * Best for:
 * - High contention scenarios
 * - When conflicts are likely
 * - When retries are expensive
 * - Critical sections requiring serialized access
 *
 * Trade-offs:
 * + No version conflicts (no retries needed)
 * + Guaranteed consistency
 * - Reduced concurrency (blocking)
 * - Potential for lock waits and timeouts
 * - Risk of deadlocks with multiple locks
 *
 * Implementation:
 * - JPA LockModeType.PESSIMISTIC_WRITE
 * - PostgreSQL SELECT FOR UPDATE
 * - Lock acquisition time measurement
 * - Automatic lock release on transaction commit
 *
 * @see com.oltp.demo.repository.AccountRepository#findByIdWithPessimisticLock
 * @see <a href="specs/001-oltp-core-demo/spec.md">US2: Concurrency and Conflict Handling</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PessimisticLockingService {

    private final AccountRepository accountRepository;
    private final MetricsHelper metricsHelper;

    /**
     * Demonstrates concurrent updates with pessimistic locking.
     *
     * Each transaction acquires an exclusive lock (SELECT FOR UPDATE) before
     * updating the account. Other transactions wait for the lock to be released.
     *
     * This results in serialized execution - one transaction at a time.
     *
     * Metrics tracked:
     * - Total lock wait time
     * - Average lock wait time
     * - Maximum lock wait time
     * - Operations per second
     * - Zero retries (no optimistic lock conflicts)
     *
     * @param accountId account to update concurrently
     * @param amount amount to add per operation
     * @param concurrentOperations number of concurrent operations
     * @return result with lock wait statistics
     */
    public PessimisticLockingResult executeConcurrentUpdates(
            Long accountId,
            BigDecimal amount,
            int concurrentOperations) {

        log.info("Starting pessimistic locking demo: account={}, amount={}, operations={}",
                accountId, amount, concurrentOperations);

        // Get initial state
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        BigDecimal initialBalance = account.getBalance();

        log.info("Initial balance: {}", initialBalance);

        // Tracking counters
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicLong totalLockWaitMs = new AtomicLong(0);
        AtomicLong maxLockWaitMs = new AtomicLong(0);

        // Create executor
        ExecutorService executor = Executors.newFixedThreadPool(
            Math.min(concurrentOperations, 20)  // Max 20 threads
        );

        List<CompletableFuture<LockAcquisitionOutcome>> futures = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        // Launch concurrent updates
        for (int i = 0; i < concurrentOperations; i++) {
            final int operationId = i;
            CompletableFuture<LockAcquisitionOutcome> future = CompletableFuture.supplyAsync(() ->
                updateAccountWithPessimisticLock(accountId, amount, operationId),
                executor
            );
            futures.add(future);
        }

        // Wait for all operations to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long duration = System.currentTimeMillis() - startTime;

        // Collect results
        for (CompletableFuture<LockAcquisitionOutcome> future : futures) {
            try {
                LockAcquisitionOutcome outcome = future.get();
                if (outcome.success) {
                    successCount.incrementAndGet();
                    totalLockWaitMs.addAndGet(outcome.lockWaitMs);
                    maxLockWaitMs.updateAndGet(current ->
                        Math.max(current, outcome.lockWaitMs)
                    );
                } else {
                    failureCount.incrementAndGet();
                }
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

        BigDecimal expectedBalance = initialBalance.add(
            amount.multiply(BigDecimal.valueOf(successCount.get()))
        );
        boolean balanceCorrect = finalBalance.compareTo(expectedBalance) == 0;

        // Calculate statistics
        double avgLockWaitMs = successCount.get() > 0 ?
            (double) totalLockWaitMs.get() / successCount.get() : 0;
        double operationsPerSecond = duration > 0 ?
            (double) successCount.get() / duration * 1000 : 0;

        log.info("Pessimistic locking demo completed:");
        log.info("- Success: {}/{}", successCount.get(), concurrentOperations);
        log.info("- Failures: {}", failureCount.get());
        log.info("- Total lock wait time: {}ms", totalLockWaitMs.get());
        log.info("- Avg lock wait time: {:.2f}ms", avgLockWaitMs);
        log.info("- Max lock wait time: {}ms", maxLockWaitMs.get());
        log.info("- Operations per second: {:.1f}", operationsPerSecond);
        log.info("- Balance: {} → {} (expected: {})", initialBalance, finalBalance, expectedBalance);
        log.info("- Balance correct: {}", balanceCorrect);
        log.info("- Duration: {}ms", duration);

        return new PessimisticLockingResult(
            concurrentOperations,
            successCount.get(),
            failureCount.get(),
            totalLockWaitMs.get(),
            avgLockWaitMs,
            maxLockWaitMs.get(),
            operationsPerSecond,
            initialBalance,
            finalBalance,
            expectedBalance,
            balanceCorrect,
            duration
        );
    }

    /**
     * Updates account with pessimistic lock (SELECT FOR UPDATE).
     *
     * Lock acquisition process:
     * 1. Begin transaction
     * 2. SELECT FOR UPDATE (blocks if another transaction has lock)
     * 3. Update data
     * 4. Commit (releases lock)
     *
     * Lock wait time is measured from transaction start to successful lock acquisition.
     *
     * @param accountId account to update
     * @param amount amount to add
     * @param operationId operation identifier for logging
     * @return outcome with lock wait time
     */
    @Transactional
    protected LockAcquisitionOutcome updateAccountWithPessimisticLock(
            Long accountId,
            BigDecimal amount,
            int operationId) {

        long threadId = Thread.currentThread().getId();
        long lockAcquireStart = System.currentTimeMillis();

        try {
            // Acquire pessimistic lock (SELECT FOR UPDATE)
            // This will block if another transaction holds the lock
            Account account = accountRepository.findByIdWithPessimisticLock(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));

            long lockAcquireEnd = System.currentTimeMillis();
            long lockWaitMs = lockAcquireEnd - lockAcquireStart;

            metricsHelper.recordPessimisticLock();

            log.debug("Operation {} (thread {}) acquired lock after {}ms",
                    operationId, threadId, lockWaitMs);

            // Update balance
            BigDecimal balanceBefore = account.getBalance();
            account.credit(amount);

            // Save (lock will be released on transaction commit)
            accountRepository.saveAndFlush(account);

            log.debug("Operation {} (thread {}) completed: balance {} → {}",
                    operationId, threadId, balanceBefore, account.getBalance());

            return new LockAcquisitionOutcome(true, lockWaitMs);

        } catch (Exception e) {
            log.error("Operation {} (thread {}) failed: {}",
                    operationId, threadId, e.getMessage());
            return new LockAcquisitionOutcome(false, 0);
        }
    }

    /**
     * Demonstrates serialized transaction execution.
     *
     * Shows that with pessimistic locking, transactions execute one at a time
     * in the order they acquire the lock.
     *
     * @param accountId account to operate on
     * @param operations list of operations (amounts) to execute serially
     * @return result with execution timeline
     */
    @Transactional
    public SerializedExecutionResult executeSerializedTransactions(
            Long accountId,
            List<BigDecimal> operations) {

        log.info("Executing {} serialized transactions with pessimistic locking", operations.size());

        Account account = accountRepository.findByIdWithPessimisticLock(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        BigDecimal initialBalance = account.getBalance();
        List<BigDecimal> balanceHistory = new ArrayList<>();
        balanceHistory.add(initialBalance);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < operations.size(); i++) {
            BigDecimal amount = operations.get(i);

            log.debug("Executing operation {}: adding {}", i + 1, amount);

            account.credit(amount);
            accountRepository.saveAndFlush(account);

            balanceHistory.add(account.getBalance());
        }

        long duration = System.currentTimeMillis() - startTime;
        BigDecimal finalBalance = account.getBalance();

        log.info("Serialized execution completed: {} operations in {}ms",
                operations.size(), duration);
        log.info("Balance progression: {}", balanceHistory);

        return new SerializedExecutionResult(
            operations.size(),
            initialBalance,
            finalBalance,
            balanceHistory,
            duration
        );
    }

    /**
     * Measures lock acquisition time for a single operation.
     *
     * Useful for understanding lock wait times under different load conditions.
     *
     * @param accountId account to lock
     * @return lock acquisition time in milliseconds
     */
    @Transactional
    public long measureLockAcquisitionTime(Long accountId) {
        long startTime = System.currentTimeMillis();

        accountRepository.findByIdWithPessimisticLock(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        long lockWaitMs = System.currentTimeMillis() - startTime;

        log.debug("Lock acquired in {}ms", lockWaitMs);

        return lockWaitMs;
    }

    /**
     * Pessimistic locking result DTO.
     */
    public record PessimisticLockingResult(
        int totalOperations,
        int successfulOperations,
        int failedOperations,
        long totalLockWaitMs,
        double avgLockWaitMs,
        long maxLockWaitMs,
        double operationsPerSecond,
        BigDecimal initialBalance,
        BigDecimal finalBalance,
        BigDecimal expectedBalance,
        boolean balanceCorrect,
        long durationMs
    ) {}

    /**
     * Serialized execution result DTO.
     */
    public record SerializedExecutionResult(
        int operationsExecuted,
        BigDecimal initialBalance,
        BigDecimal finalBalance,
        List<BigDecimal> balanceHistory,
        long durationMs
    ) {}

    /**
     * Lock acquisition outcome for single operation.
     */
    private record LockAcquisitionOutcome(boolean success, long lockWaitMs) {}
}
