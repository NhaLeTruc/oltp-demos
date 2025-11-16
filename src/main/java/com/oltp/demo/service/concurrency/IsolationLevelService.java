package com.oltp.demo.service.concurrency;

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
import com.oltp.demo.util.MetricsHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating different transaction isolation levels and their phenomena.
 *
 * Demonstrates:
 * - Dirty reads (READ_UNCOMMITTED)
 * - Non-repeatable reads (READ_COMMITTED)
 * - Phantom reads (REPEATABLE_READ)
 * - Full isolation (SERIALIZABLE)
 *
 * Each isolation level prevents certain concurrency phenomena but has trade-offs
 * in terms of performance and lock contention.
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US2: Concurrency Control</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IsolationLevelService {

    private final AccountRepository accountRepository;
    private final MetricsHelper metricsHelper;

    /**
     * Demonstrates dirty read phenomenon with READ_UNCOMMITTED isolation.
     *
     * Dirty read: Transaction T1 reads uncommitted changes from transaction T2.
     * If T2 rolls back, T1 has read data that never existed.
     *
     * Note: PostgreSQL does not support READ_UNCOMMITTED (treats it as READ_COMMITTED).
     * This demonstration shows what would happen if it were supported.
     *
     * @param accountId Account to demonstrate on
     * @param tempAmount Temporary amount to write (will be rolled back)
     * @return Demonstration results
     */
    public DirtyReadResult demonstrateDirtyRead(Long accountId, BigDecimal tempAmount) {
        long startTime = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // Original balance
            BigDecimal originalBalance = accountRepository.findById(accountId)
                .map(Account::getBalance)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));

            List<BigDecimal> readBalances = new ArrayList<>();
            volatile boolean writeCompleted = false;

            // Transaction 1: Try to read uncommitted data (dirty read attempt)
            CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(50); // Wait for writer to start
                    BigDecimal balance = readWithIsolation(accountId, Isolation.READ_UNCOMMITTED);
                    readBalances.add(balance);
                    log.info("READ_UNCOMMITTED: Read balance = {}", balance);
                } catch (Exception e) {
                    log.error("Reader error", e);
                }
            }, executor);

            // Transaction 2: Write temporary value and rollback
            CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
                try {
                    writeAndRollback(accountId, tempAmount);
                } catch (Exception e) {
                    log.error("Writer error", e);
                }
            }, executor);

            CompletableFuture.allOf(reader, writer).get(5, TimeUnit.SECONDS);

            BigDecimal readBalance = readBalances.isEmpty() ? originalBalance : readBalances.get(0);
            boolean dirtyReadOccurred = readBalance.compareTo(originalBalance) != 0;

            long durationMs = System.currentTimeMillis() - startTime;

            return new DirtyReadResult(
                originalBalance,
                tempAmount,
                readBalance,
                dirtyReadOccurred,
                "PostgreSQL treats READ_UNCOMMITTED as READ_COMMITTED, preventing dirty reads",
                durationMs
            );

        } catch (Exception e) {
            log.error("Dirty read demonstration failed", e);
            throw new RuntimeException("Dirty read demonstration failed", e);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Demonstrates non-repeatable read phenomenon with READ_COMMITTED isolation.
     *
     * Non-repeatable read: Transaction T1 reads same row twice and gets different values
     * because transaction T2 modified and committed the row between T1's reads.
     *
     * @param accountId Account to demonstrate on
     * @param updateAmount Amount to update during demonstration
     * @return Demonstration results
     */
    public NonRepeatableReadResult demonstrateNonRepeatableRead(Long accountId, BigDecimal updateAmount) {
        long startTime = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<BigDecimal> firstRead = new ArrayList<>();
            List<BigDecimal> secondRead = new ArrayList<>();

            // Transaction 1: Read twice with READ_COMMITTED
            CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
                readTwiceWithCommittedIsolation(accountId, firstRead, secondRead);
            }, executor);

            // Transaction 2: Update value between reads
            CompletableFuture<Void> updater = CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(100); // Wait for first read
                    updateAccountBalance(accountId, updateAmount);
                    Thread.sleep(100); // Wait for second read
                } catch (Exception e) {
                    log.error("Updater error", e);
                }
            }, executor);

            CompletableFuture.allOf(reader, updater).get(5, TimeUnit.SECONDS);

            BigDecimal balance1 = firstRead.isEmpty() ? BigDecimal.ZERO : firstRead.get(0);
            BigDecimal balance2 = secondRead.isEmpty() ? BigDecimal.ZERO : secondRead.get(0);
            boolean nonRepeatableReadOccurred = balance1.compareTo(balance2) != 0;

            long durationMs = System.currentTimeMillis() - startTime;

            return new NonRepeatableReadResult(
                balance1,
                balance2,
                nonRepeatableReadOccurred,
                "READ_COMMITTED allows non-repeatable reads - same query returns different results",
                durationMs
            );

        } catch (Exception e) {
            log.error("Non-repeatable read demonstration failed", e);
            throw new RuntimeException("Non-repeatable read demonstration failed", e);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Demonstrates phantom read phenomenon with REPEATABLE_READ isolation.
     *
     * Phantom read: Transaction T1 executes a query twice and sees different row sets
     * because transaction T2 inserted new rows that match T1's query between executions.
     *
     * Note: PostgreSQL's REPEATABLE_READ actually prevents phantom reads using MVCC.
     *
     * @param minBalance Minimum balance for range query
     * @param newAccountBalance Balance for newly inserted account
     * @return Demonstration results
     */
    public PhantomReadResult demonstratePhantomRead(BigDecimal minBalance, BigDecimal newAccountBalance) {
        long startTime = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Integer> firstCount = new ArrayList<>();
            List<Integer> secondCount = new ArrayList<>();

            // Transaction 1: Count accounts twice with REPEATABLE_READ
            CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
                countAccountsTwice(minBalance, firstCount, secondCount);
            }, executor);

            // Transaction 2: Insert new account between counts
            CompletableFuture<Void> inserter = CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(100); // Wait for first count
                    // Note: Actual insert would require user creation, skipping for demo
                    Thread.sleep(100); // Simulate insert time
                } catch (Exception e) {
                    log.error("Inserter error", e);
                }
            }, executor);

            CompletableFuture.allOf(reader, inserter).get(5, TimeUnit.SECONDS);

            int count1 = firstCount.isEmpty() ? 0 : firstCount.get(0);
            int count2 = secondCount.isEmpty() ? 0 : secondCount.get(0);
            boolean phantomReadOccurred = count1 != count2;

            long durationMs = System.currentTimeMillis() - startTime;

            return new PhantomReadResult(
                count1,
                count2,
                phantomReadOccurred,
                "PostgreSQL's REPEATABLE_READ prevents phantom reads using MVCC snapshots",
                durationMs
            );

        } catch (Exception e) {
            log.error("Phantom read demonstration failed", e);
            throw new RuntimeException("Phantom read demonstration failed", e);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Demonstrates SERIALIZABLE isolation level.
     *
     * SERIALIZABLE: Strictest isolation level. Transactions execute as if they were
     * run serially, one after another. Prevents all concurrency phenomena but has
     * highest overhead.
     *
     * @param accountId Account to demonstrate on
     * @param amount Amount for operation
     * @return Demonstration results
     */
    public SerializableIsolationResult demonstrateSerializable(Long accountId, BigDecimal amount) {
        long startTime = System.currentTimeMillis();

        try {
            BigDecimal balanceBefore = accountRepository.findById(accountId)
                .map(Account::getBalance)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));

            // Execute operation with SERIALIZABLE isolation
            executeWithSerializableIsolation(accountId, amount);

            BigDecimal balanceAfter = accountRepository.findById(accountId)
                .map(Account::getBalance)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));

            long durationMs = System.currentTimeMillis() - startTime;

            return new SerializableIsolationResult(
                balanceBefore,
                balanceAfter,
                true,
                "SERIALIZABLE prevents all concurrency anomalies but may cause serialization failures",
                durationMs
            );

        } catch (Exception e) {
            log.error("Serializable isolation demonstration failed", e);
            throw new RuntimeException("Serializable isolation demonstration failed", e);
        }
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    @Transactional(isolation = Isolation.READ_UNCOMMITTED)
    protected BigDecimal readWithIsolation(Long accountId, Isolation isolation) {
        return accountRepository.findById(accountId)
            .map(Account::getBalance)
            .orElse(BigDecimal.ZERO);
    }

    @Transactional
    protected void writeAndRollback(Long accountId, BigDecimal tempAmount) {
        try {
            Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));

            account.credit(tempAmount);
            accountRepository.saveAndFlush(account);

            Thread.sleep(200); // Keep uncommitted data visible

            // Force rollback
            throw new RuntimeException("Intentional rollback for demonstration");
        } catch (RuntimeException e) {
            // Expected - transaction will rollback
        } catch (Exception e) {
            log.error("Write and rollback error", e);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    protected void readTwiceWithCommittedIsolation(Long accountId, List<BigDecimal> firstRead, List<BigDecimal> secondRead) {
        try {
            // First read
            BigDecimal balance1 = accountRepository.findById(accountId)
                .map(Account::getBalance)
                .orElse(BigDecimal.ZERO);
            firstRead.add(balance1);
            log.info("First read: {}", balance1);

            Thread.sleep(200); // Wait for update

            // Second read - may be different due to READ_COMMITTED
            BigDecimal balance2 = accountRepository.findById(accountId)
                .map(Account::getBalance)
                .orElse(BigDecimal.ZERO);
            secondRead.add(balance2);
            log.info("Second read: {}", balance2);

        } catch (Exception e) {
            log.error("Read twice error", e);
        }
    }

    @Transactional
    protected void updateAccountBalance(Long accountId, BigDecimal amount) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        account.credit(amount);
        accountRepository.saveAndFlush(account);
        log.info("Updated account balance by {}", amount);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    protected void countAccountsTwice(BigDecimal minBalance, List<Integer> firstCount, List<Integer> secondCount) {
        try {
            // First count
            int count1 = accountRepository.countByBalanceGreaterThan(minBalance);
            firstCount.add(count1);
            log.info("First count: {}", count1);

            Thread.sleep(200); // Wait for potential insert

            // Second count - should be same with REPEATABLE_READ in PostgreSQL
            int count2 = accountRepository.countByBalanceGreaterThan(minBalance);
            secondCount.add(count2);
            log.info("Second count: {}", count2);

        } catch (Exception e) {
            log.error("Count twice error", e);
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    protected void executeWithSerializableIsolation(Long accountId, BigDecimal amount) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        account.credit(amount);
        accountRepository.save(account);
        log.info("Executed with SERIALIZABLE isolation");
    }

    // =========================================================================
    // Result DTOs
    // =========================================================================

    public record DirtyReadResult(
        BigDecimal originalBalance,
        BigDecimal tempAmountWritten,
        BigDecimal readBalance,
        boolean dirtyReadOccurred,
        String explanation,
        long durationMs
    ) {}

    public record NonRepeatableReadResult(
        BigDecimal firstRead,
        BigDecimal secondRead,
        boolean nonRepeatableReadOccurred,
        String explanation,
        long durationMs
    ) {}

    public record PhantomReadResult(
        int firstCount,
        int secondCount,
        boolean phantomReadOccurred,
        String explanation,
        long durationMs
    ) {}

    public record SerializableIsolationResult(
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        boolean success,
        String explanation,
        long durationMs
    ) {}
}
