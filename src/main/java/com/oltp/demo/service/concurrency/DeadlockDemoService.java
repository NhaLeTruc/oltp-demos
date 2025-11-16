package com.oltp.demo.service.concurrency;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oltp.demo.domain.Account;
import com.oltp.demo.repository.AccountRepository;
import com.oltp.demo.util.MetricsHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating Deadlock Detection and Recovery.
 *
 * Deadlock occurs when:
 * - Transaction A holds lock on Resource X, waits for Resource Y
 * - Transaction B holds lock on Resource Y, waits for Resource X
 * - Neither can proceed (circular wait)
 *
 * Classic deadlock scenario:
 * - Transaction 1: Transfer from Account A to Account B
 * - Transaction 2: Transfer from Account B to Account A
 * - Both try to lock accounts in different order
 *
 * PostgreSQL deadlock detection:
 * - Monitors lock wait graph
 * - Detects cycles (deadlocks)
 * - Aborts one transaction (deadlock victim)
 * - Throws DeadlockLoserDataAccessException
 *
 * Recovery strategy:
 * - Catch deadlock exception
 * - Retry transaction after brief delay
 * - Use random backoff to avoid repeated deadlocks
 * - Track deadlock frequency for monitoring
 *
 * Prevention strategies:
 * - Always acquire locks in same order
 * - Use application-level ordering (e.g., by account ID)
 * - Keep transactions short
 * - Use appropriate isolation levels
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US2: Concurrency and Conflict Handling</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadlockDemoService {

    private final AccountRepository accountRepository;
    private final MetricsHelper metricsHelper;

    private static final int MAX_DEADLOCK_RETRIES = 3;
    private static final int BASE_DEADLOCK_BACKOFF_MS = 50;

    /**
     * Demonstrates deadlock scenario with bidirectional transfers.
     *
     * Scenario:
     * - Thread 1: Transfer A → B
     * - Thread 2: Transfer B → A
     * - Both execute concurrently
     * - High probability of deadlock
     *
     * PostgreSQL detects the deadlock and aborts one transaction.
     * The aborted transaction is retried automatically.
     *
     * @param accountIdA first account
     * @param accountIdB second account
     * @param amount transfer amount
     * @return result with deadlock statistics
     */
    public DeadlockResult demonstrateBidirectionalTransferDeadlock(
            Long accountIdA,
            Long accountIdB,
            BigDecimal amount) {

        log.info("Demonstrating deadlock scenario: A={}, B={}, amount={}",
                accountIdA, accountIdB, amount);

        // Tracking counters
        AtomicInteger deadlockCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger totalRetries = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        long startTime = System.currentTimeMillis();

        // Launch bidirectional transfers concurrently
        CompletableFuture<TransferOutcome> transfer1 = CompletableFuture.supplyAsync(() ->
            transferWithDeadlockRecovery(accountIdA, accountIdB, amount, "A→B", deadlockCount, totalRetries),
            executor
        );

        CompletableFuture<TransferOutcome> transfer2 = CompletableFuture.supplyAsync(() ->
            transferWithDeadlockRecovery(accountIdB, accountIdA, amount, "B→A", deadlockCount, totalRetries),
            executor
        );

        // Wait for both to complete
        CompletableFuture.allOf(transfer1, transfer2).join();

        long duration = System.currentTimeMillis() - startTime;

        // Collect results
        try {
            TransferOutcome outcome1 = transfer1.get();
            if (outcome1.success) successCount.incrementAndGet();
            else failureCount.incrementAndGet();

            TransferOutcome outcome2 = transfer2.get();
            if (outcome2.success) successCount.incrementAndGet();
            else failureCount.incrementAndGet();
        } catch (Exception e) {
            log.error("Failed to get transfer results: {}", e.getMessage());
        }

        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("Deadlock demonstration completed:");
        log.info("- Deadlocks detected: {}", deadlockCount.get());
        log.info("- Total retries: {}", totalRetries.get());
        log.info("- Successful transfers: {}", successCount.get());
        log.info("- Failed transfers: {}", failureCount.get());
        log.info("- Duration: {}ms", duration);

        return new DeadlockResult(
            deadlockCount.get(),
            totalRetries.get(),
            successCount.get(),
            failureCount.get(),
            duration
        );
    }

    /**
     * Executes transfer with deadlock detection and retry.
     *
     * Lock acquisition order (to cause deadlock):
     * - Lock source account first
     * - Then lock destination account
     * - When two transfers run in opposite directions, deadlock occurs
     *
     * Recovery:
     * - Catch DeadlockLoserDataAccessException
     * - Wait with exponential backoff
     * - Retry transaction
     * - Maximum 3 retries
     *
     * @param fromAccountId source account
     * @param toAccountId destination account
     * @param amount transfer amount
     * @param transferName name for logging
     * @param deadlockCounter counter for deadlock occurrences
     * @param retryCounter counter for total retries
     * @return outcome with success status
     */
    protected TransferOutcome transferWithDeadlockRecovery(
            Long fromAccountId,
            Long toAccountId,
            BigDecimal amount,
            String transferName,
            AtomicInteger deadlockCounter,
            AtomicInteger retryCounter) {

        int attempt = 0;

        while (attempt <= MAX_DEADLOCK_RETRIES) {
            try {
                executeTransferWithPessimisticLocks(fromAccountId, toAccountId, amount, transferName);
                log.info("Transfer {} succeeded on attempt {}", transferName, attempt + 1);
                return new TransferOutcome(true, attempt);

            } catch (DeadlockLoserDataAccessException e) {
                deadlockCounter.incrementAndGet();
                metricsHelper.recordDeadlock();

                attempt++;
                retryCounter.incrementAndGet();

                log.warn("Transfer {} - DEADLOCK detected (attempt {})", transferName, attempt);

                if (attempt > MAX_DEADLOCK_RETRIES) {
                    log.error("Transfer {} exhausted deadlock retries", transferName);
                    return new TransferOutcome(false, attempt);
                }

                // Exponential backoff with random jitter
                int backoffMs = BASE_DEADLOCK_BACKOFF_MS * (1 << (attempt - 1)); // 50, 100, 200ms
                int jitter = (int) (Math.random() * backoffMs);
                int sleepMs = backoffMs + jitter;

                log.info("Transfer {} retrying after {}ms backoff...", transferName, sleepMs);

                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new TransferOutcome(false, attempt);
                }

            } catch (CannotAcquireLockException e) {
                log.error("Transfer {} - cannot acquire lock: {}", transferName, e.getMessage());
                return new TransferOutcome(false, attempt);

            } catch (Exception e) {
                log.error("Transfer {} - unexpected error: {}", transferName, e.getMessage(), e);
                return new TransferOutcome(false, attempt);
            }
        }

        return new TransferOutcome(false, attempt);
    }

    /**
     * Executes transfer with pessimistic locks (can cause deadlock).
     *
     * Lock acquisition order:
     * 1. Lock source account (SELECT FOR UPDATE)
     * 2. Lock destination account (SELECT FOR UPDATE)
     * 3. Perform transfer
     *
     * If another transaction locks these accounts in opposite order,
     * PostgreSQL will detect the deadlock and abort one transaction.
     *
     * @param fromAccountId source account
     * @param toAccountId destination account
     * @param amount transfer amount
     * @param transferName name for logging
     */
    @Transactional
    protected void executeTransferWithPessimisticLocks(
            Long fromAccountId,
            Long toAccountId,
            BigDecimal amount,
            String transferName) {

        long threadId = Thread.currentThread().getId();

        log.debug("Transfer {} (thread {}) - acquiring lock on source account {}",
                transferName, threadId, fromAccountId);

        // Lock source account first (potential deadlock point)
        Account fromAccount = accountRepository.findByIdWithPessimisticLock(fromAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Source account not found"));

        log.debug("Transfer {} (thread {}) - acquired source lock, acquiring destination lock {}",
                transferName, threadId, toAccountId);

        // Small delay to increase deadlock probability (for demonstration)
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Lock destination account second (potential deadlock point)
        Account toAccount = accountRepository.findByIdWithPessimisticLock(toAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Destination account not found"));

        log.debug("Transfer {} (thread {}) - acquired both locks, performing transfer",
                transferName, threadId);

        // Perform transfer
        fromAccount.debit(amount);
        toAccount.credit(amount);

        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);
        accountRepository.flush();

        log.debug("Transfer {} (thread {}) - completed successfully",
                transferName, threadId);
    }

    /**
     * Demonstrates deadlock prevention using lock ordering.
     *
     * Prevention strategy:
     * - Always lock accounts in same order (by ID)
     * - Prevents circular wait condition
     * - Eliminates deadlock possibility
     *
     * @param accountIdA first account
     * @param accountIdB second account
     * @param amount transfer amount
     * @param aToB if true, transfer A→B; if false, transfer B→A
     * @return success status
     */
    @Transactional
    public boolean transferWithDeadlockPrevention(
            Long accountIdA,
            Long accountIdB,
            BigDecimal amount,
            boolean aToB) {

        log.info("Transfer with deadlock prevention: {} → {}",
                aToB ? accountIdA : accountIdB,
                aToB ? accountIdB : accountIdA);

        // Always lock in ID order to prevent deadlock
        Long lowerAccountId = Math.min(accountIdA, accountIdB);
        Long higherAccountId = Math.max(accountIdA, accountIdB);

        log.debug("Locking accounts in order: {} then {}", lowerAccountId, higherAccountId);

        // Lock in consistent order
        Account lowerAccount = accountRepository.findByIdWithPessimisticLock(lowerAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        Account higherAccount = accountRepository.findByIdWithPessimisticLock(higherAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        // Determine which is source and which is destination
        Account fromAccount = aToB ?
            (accountIdA.equals(lowerAccountId) ? lowerAccount : higherAccount) :
            (accountIdB.equals(lowerAccountId) ? lowerAccount : higherAccount);

        Account toAccount = aToB ?
            (accountIdB.equals(lowerAccountId) ? lowerAccount : higherAccount) :
            (accountIdA.equals(lowerAccountId) ? lowerAccount : higherAccount);

        // Perform transfer
        fromAccount.debit(amount);
        toAccount.credit(amount);

        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        log.info("Transfer completed without deadlock");

        return true;
    }

    /**
     * Deadlock result DTO.
     */
    public record DeadlockResult(
        int deadlocksDetected,
        int totalRetries,
        int successfulTransfers,
        int failedTransfers,
        long durationMs
    ) {}

    /**
     * Transfer outcome for single operation.
     */
    private record TransferOutcome(boolean success, int attempts) {}
}
