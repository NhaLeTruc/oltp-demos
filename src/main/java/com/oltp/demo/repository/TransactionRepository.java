package com.oltp.demo.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.oltp.demo.domain.Transaction;

/**
 * Repository for Transaction entity.
 *
 * Central to ACID demonstrations, this repository provides methods
 * for querying transactions by various criteria including correlation ID
 * for distributed tracing.
 *
 * Indexes used:
 * - idx_transactions_correlation_id: Fast lookup by correlation ID
 * - idx_transactions_status: Filter by status
 * - idx_transactions_created_at: Recent transactions (DESC order)
 * - idx_transactions_completed_at: Completed transactions (partial index)
 *
 * @see com.oltp.demo.domain.Transaction
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * Find transaction by correlation ID.
     *
     * Used for distributed tracing - correlate transactions across
     * logs, metrics, and traces.
     *
     * Uses index: idx_transactions_correlation_id
     *
     * @param correlationId the correlation UUID
     * @return Optional containing the transaction if found
     */
    Optional<Transaction> findByCorrelationId(UUID correlationId);

    /**
     * Find all transactions with a specific correlation ID.
     *
     * In most cases there's only one, but this handles edge cases
     * where correlation IDs might be reused (shouldn't happen with UUIDs).
     *
     * @param correlationId the correlation UUID
     * @return list of transactions with the correlation ID
     */
    List<Transaction> findAllByCorrelationId(UUID correlationId);

    /**
     * Find transactions by status.
     *
     * Uses index: idx_transactions_status
     *
     * @param status the transaction status
     * @return list of transactions with the specified status
     */
    List<Transaction> findByStatus(Transaction.TransactionStatus status);

    /**
     * Find transactions by type.
     *
     * @param transactionType the transaction type
     * @return list of transactions of the specified type
     */
    List<Transaction> findByTransactionType(Transaction.TransactionType transactionType);

    /**
     * Find transactions involving a specific account (from or to).
     *
     * Uses indexes: idx_transactions_from_account, idx_transactions_to_account
     *
     * @param accountId the account ID
     * @return list of transactions involving the account
     */
    @Query("SELECT t FROM Transaction t WHERE t.fromAccount.id = :accountId OR t.toAccount.id = :accountId")
    List<Transaction> findByAccountId(@Param("accountId") Long accountId);

    /**
     * Find recent transactions (last N days) ordered by creation time.
     *
     * Uses index: idx_transactions_created_at (DESC order)
     *
     * @param since the cutoff timestamp
     * @return list of recent transactions
     */
    @Query("SELECT t FROM Transaction t WHERE t.createdAt >= :since ORDER BY t.createdAt DESC")
    List<Transaction> findRecentTransactions(@Param("since") Instant since);

    /**
     * Find completed transactions for durability verification.
     *
     * Used in crash recovery demonstrations to verify committed
     * transactions survived restart.
     *
     * Uses partial index: idx_transactions_completed_at
     *
     * @return list of completed transactions
     */
    @Query("SELECT t FROM Transaction t WHERE t.status = 'COMPLETED' ORDER BY t.completedAt DESC")
    List<Transaction> findCompletedTransactions();

    /**
     * Find pending transactions (for monitoring stuck transactions).
     *
     * @return list of pending transactions
     */
    @Query("SELECT t FROM Transaction t WHERE t.status = 'PENDING' ORDER BY t.createdAt DESC")
    List<Transaction> findPendingTransactions();

    /**
     * Find failed transactions with error messages.
     *
     * Useful for debugging and error analysis.
     *
     * @return list of failed transactions
     */
    @Query("SELECT t FROM Transaction t WHERE t.status = 'FAILED' AND t.errorMessage IS NOT NULL ORDER BY t.createdAt DESC")
    List<Transaction> findFailedTransactions();

    /**
     * Count transactions by status.
     *
     * More efficient than loading all transactions and counting in memory.
     *
     * @param status the transaction status
     * @return count of transactions with the status
     */
    long countByStatus(Transaction.TransactionStatus status);

    /**
     * Find transaction with accounts eagerly loaded.
     *
     * Uses JOIN FETCH to avoid N+1 query problem.
     * Useful when you need full transaction details including accounts.
     *
     * @param id the transaction ID
     * @return Optional containing the transaction with relationships if found
     */
    @Query("SELECT t FROM Transaction t " +
           "LEFT JOIN FETCH t.fromAccount " +
           "LEFT JOIN FETCH t.toAccount " +
           "WHERE t.id = :id")
    Optional<Transaction> findByIdWithAccounts(@Param("id") Long id);
}
