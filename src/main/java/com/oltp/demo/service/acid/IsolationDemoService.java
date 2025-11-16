package com.oltp.demo.service.acid;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.oltp.demo.domain.Account;
import com.oltp.demo.repository.AccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating ACID Isolation property.
 *
 * Isolation: Concurrent transactions execute as if they were serial.
 * Transactions don't see each other's intermediate states.
 *
 * Isolation Levels (PostgreSQL):
 * 1. READ_UNCOMMITTED: Not supported in PostgreSQL (upgraded to READ_COMMITTED)
 * 2. READ_COMMITTED: Default - sees only committed data
 * 3. REPEATABLE_READ: Snapshot isolation - consistent view throughout txn
 * 4. SERIALIZABLE: Strictest - true serializability
 *
 * Demonstrations:
 * 1. READ_COMMITTED: Prevents dirty reads
 * 2. REPEATABLE_READ: Prevents non-repeatable reads
 * 3. SERIALIZABLE: Prevents phantom reads
 * 4. Concurrent transfers: Shows isolation in action
 *
 * Key mechanisms:
 * - Spring @Transactional(isolation = ...)
 * - PostgreSQL MVCC (Multi-Version Concurrency Control)
 * - Row-level locking
 * - Optimistic locking (@Version)
 *
 * Constitution.md alignment:
 * - Principle III: Concurrency & Scalability
 * - Design for concurrent access from day one
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US1: ACID Transaction Guarantees</a>
 * @see <a href="specs/001-oltp-core-demo/spec.md">US2: Concurrency and Conflict Handling</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IsolationDemoService {

    private final AccountRepository accountRepository;

    /**
     * Demonstrates READ_COMMITTED isolation level.
     *
     * READ_COMMITTED prevents dirty reads:
     * - Transaction A cannot see uncommitted changes from Transaction B
     * - Only committed data is visible
     *
     * Default isolation level in PostgreSQL and Spring Boot.
     *
     * @param accountId account to read
     * @return isolation demonstration result
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IsolationDemoResult demonstrateReadCommitted(Long accountId) {
        log.info("Demonstrating READ_COMMITTED isolation: account={}", accountId);

        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        BigDecimal initialBalance = account.getBalance();
        log.info("Initial balance read: {}", initialBalance);

        // In READ_COMMITTED, if another transaction modifies this account
        // and commits, we WILL see the new value on next read
        // But we WON'T see uncommitted changes (dirty reads prevented)

        // Sleep briefly to allow concurrent modification (in real demo)
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Read again - may see different value if another txn committed
        account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        BigDecimal secondBalance = account.getBalance();

        log.info("Second balance read: {}", secondBalance);

        boolean balanceChanged = !initialBalance.equals(secondBalance);

        return new IsolationDemoResult(
            "READ_COMMITTED",
            balanceChanged ? "Balance changed between reads (expected in READ_COMMITTED)" :
                           "Balance unchanged (no concurrent modification)",
            initialBalance,
            secondBalance
        );
    }

    /**
     * Demonstrates REPEATABLE_READ isolation level.
     *
     * REPEATABLE_READ prevents non-repeatable reads:
     * - Transaction sees a consistent snapshot of the database
     * - Same query returns same results throughout transaction
     * - Uses snapshot isolation (MVCC)
     *
     * In PostgreSQL, this is implemented via MVCC.
     * Concurrent updates cause serialization failures.
     *
     * @param accountId account to read
     * @return isolation demonstration result
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public IsolationDemoResult demonstrateRepeatableRead(Long accountId) {
        log.info("Demonstrating REPEATABLE_READ isolation: account={}", accountId);

        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        BigDecimal initialBalance = account.getBalance();
        log.info("Initial balance read (REPEATABLE_READ snapshot): {}", initialBalance);

        // Sleep to allow potential concurrent modification
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Read again - should see SAME value even if another txn committed
        // This is because REPEATABLE_READ uses snapshot isolation
        account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        BigDecimal secondBalance = account.getBalance();

        log.info("Second balance read: {}", secondBalance);

        boolean balanceChanged = !initialBalance.equals(secondBalance);

        if (balanceChanged) {
            log.warn("Balance changed in REPEATABLE_READ - unexpected!");
        }

        return new IsolationDemoResult(
            "REPEATABLE_READ",
            balanceChanged ? "Balance changed (unexpected in REPEATABLE_READ!)" :
                           "Balance unchanged (snapshot isolation working)",
            initialBalance,
            secondBalance
        );
    }

    /**
     * Demonstrates SERIALIZABLE isolation level.
     *
     * SERIALIZABLE is the strictest isolation level:
     * - Transactions execute as if they were completely serial
     * - Prevents all anomalies (dirty reads, non-repeatable reads, phantoms)
     * - May cause serialization failures if conflicts detected
     *
     * PostgreSQL implements SERIALIZABLE using SSI (Serializable Snapshot Isolation).
     *
     * Trade-offs:
     * + Strongest consistency guarantees
     * - Higher risk of serialization failures
     * - Requires retry logic in application
     *
     * @param accountId account to operate on
     * @param amount amount to add
     * @return isolation demonstration result
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public IsolationDemoResult demonstrateSerializable(Long accountId, BigDecimal amount) {
        log.info("Demonstrating SERIALIZABLE isolation: account={}, amount={}", accountId, amount);

        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        BigDecimal initialBalance = account.getBalance();
        log.info("Initial balance: {}", initialBalance);

        // Perform operation
        account.credit(amount);
        accountRepository.save(account);

        BigDecimal finalBalance = account.getBalance();
        log.info("Final balance: {}", finalBalance);

        // Note: If another concurrent SERIALIZABLE transaction conflicts,
        // PostgreSQL will throw a serialization failure exception

        return new IsolationDemoResult(
            "SERIALIZABLE",
            "Transaction completed without serialization failure",
            initialBalance,
            finalBalance
        );
    }

    /**
     * Executes concurrent transfers to demonstrate isolation in action.
     *
     * Runs multiple concurrent transactions against the same account
     * to show how isolation prevents race conditions.
     *
     * Configuration:
     * - Uses READ_COMMITTED by default
     * - Each transfer is in its own transaction
     * - Optimistic locking (@Version) prevents lost updates
     *
     * Expected behavior:
     * - All transfers complete successfully
     * - Final balance = initial + (amount × count)
     * - No lost updates due to @Version optimistic locking
     *
     * @param accountId account to operate on
     * @param amount amount per transfer
     * @param concurrentCount number of concurrent operations
     * @return concurrent execution result
     */
    public ConcurrentTransferResult executeConcurrentTransfers(
            Long accountId,
            BigDecimal amount,
            int concurrentCount) {

        log.info("Executing {} concurrent transfers: account={}, amount={}",
                concurrentCount, accountId, amount);

        // Get initial balance
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        BigDecimal initialBalance = account.getBalance();
        Long initialVersion = account.getVersion();

        log.info("Initial state: balance={}, version={}", initialBalance, initialVersion);

        // Create executor for concurrent operations
        ExecutorService executor = Executors.newFixedThreadPool(
            Math.min(concurrentCount, 10)  // Max 10 threads
        );

        List<CompletableFuture<TransferOutcome>> futures = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        // Launch concurrent transfers
        for (int i = 0; i < concurrentCount; i++) {
            final int operationId = i;
            CompletableFuture<TransferOutcome> future = CompletableFuture.supplyAsync(() -> {
                return performSingleTransfer(accountId, amount, operationId);
            }, executor);
            futures.add(future);
        }

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long duration = System.currentTimeMillis() - startTime;

        // Collect results
        int successCount = 0;
        int failureCount = 0;
        int retryCount = 0;

        for (CompletableFuture<TransferOutcome> future : futures) {
            try {
                TransferOutcome outcome = future.get();
                if (outcome.success) {
                    successCount++;
                } else {
                    failureCount++;
                }
                retryCount += outcome.retryCount;
            } catch (Exception e) {
                log.error("Future failed: {}", e.getMessage());
                failureCount++;
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

        BigDecimal expectedBalance = initialBalance.add(amount.multiply(BigDecimal.valueOf(successCount)));
        boolean balanceCorrect = finalBalance.compareTo(expectedBalance) == 0;

        log.info("Final state: balance={}, version={}, expectedBalance={}, correct={}",
                finalBalance, finalVersion, expectedBalance, balanceCorrect);

        return new ConcurrentTransferResult(
            concurrentCount,
            successCount,
            failureCount,
            retryCount,
            initialBalance,
            finalBalance,
            expectedBalance,
            balanceCorrect,
            duration,
            initialVersion,
            finalVersion
        );
    }

    /**
     * Performs a single transfer with retry on optimistic lock failure.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    protected TransferOutcome performSingleTransfer(Long accountId, BigDecimal amount, int operationId) {
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found"));

                account.credit(amount);
                accountRepository.save(account);

                log.debug("Transfer {} succeeded (retries: {})", operationId, retryCount);
                return new TransferOutcome(true, retryCount);

            } catch (Exception e) {
                retryCount++;
                log.debug("Transfer {} failed (attempt {}): {}", operationId, retryCount, e.getMessage());

                if (retryCount >= maxRetries) {
                    log.error("Transfer {} exhausted retries", operationId);
                    return new TransferOutcome(false, retryCount);
                }

                // Brief backoff before retry
                try {
                    Thread.sleep(10 * retryCount);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new TransferOutcome(false, retryCount);
                }
            }
        }

        return new TransferOutcome(false, retryCount);
    }

    /**
     * Isolation demo result DTO.
     */
    public record IsolationDemoResult(
        String isolationLevel,
        String observation,
        BigDecimal initialBalance,
        BigDecimal finalBalance
    ) {}

    /**
     * Concurrent transfer result DTO.
     */
    public record ConcurrentTransferResult(
        int totalOperations,
        int successfulOperations,
        int failedOperations,
        int totalRetries,
        BigDecimal initialBalance,
        BigDecimal finalBalance,
        BigDecimal expectedBalance,
        boolean balanceCorrect,
        long durationMs,
        Long initialVersion,
        Long finalVersion
    ) {}

    /**
     * Transfer outcome for single operation.
     */
    private record TransferOutcome(boolean success, int retryCount) {}
}
