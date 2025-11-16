package com.oltp.demo.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.oltp.demo.domain.Account;

/**
 * Repository for Account entity.
 *
 * This repository is central to ACID and concurrency demonstrations.
 * Provides methods with different locking strategies:
 *
 * Optimistic Locking (default):
 * - Uses @Version column automatically
 * - Best for low-contention scenarios
 * - Fails with OptimisticLockException on concurrent modification
 *
 * Pessimistic Locking (explicit):
 * - Uses SELECT FOR UPDATE (database-level lock)
 * - Best for high-contention scenarios
 * - Blocks concurrent readers until lock is released
 *
 * @see com.oltp.demo.service.concurrency.OptimisticLockingDemoService
 * @see com.oltp.demo.service.concurrency.PessimisticLockingDemoService
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Find account by ID with optimistic locking (default behavior).
     *
     * Uses @Version column for optimistic concurrency control.
     * Multiple transactions can read simultaneously, but only one can commit.
     *
     * @param id the account ID
     * @return Optional containing the account if found
     */
    @Override
    Optional<Account> findById(Long id);

    /**
     * Find account by ID with pessimistic write lock.
     *
     * Uses SELECT FOR UPDATE - acquires exclusive row lock.
     * Other transactions are blocked from reading this row until lock is released.
     *
     * Use for high-contention scenarios where optimistic locking fails frequently.
     *
     * Example SQL: SELECT * FROM accounts WHERE id = ? FOR UPDATE
     *
     * @param id the account ID
     * @return Optional containing the account if found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithPessimisticLock(@Param("id") Long id);

    /**
     * Find account by ID with pessimistic read lock.
     *
     * Uses SELECT FOR SHARE - allows concurrent reads, blocks writes.
     * Useful when you want to ensure data doesn't change while reading.
     *
     * Example SQL: SELECT * FROM accounts WHERE id = ? FOR SHARE
     *
     * @param id the account ID
     * @return Optional containing the account if found
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithPessimisticReadLock(@Param("id") Long id);

    /**
     * Find all active accounts for a user.
     *
     * Demonstrates partial index usage (idx_accounts_user_status).
     *
     * @param userId the user ID
     * @return list of active accounts
     */
    @Query("SELECT a FROM Account a WHERE a.user.id = :userId AND a.status = 'ACTIVE'")
    List<Account> findActiveAccountsByUserId(@Param("userId") Long userId);

    /**
     * Find all accounts for a user (regardless of status).
     *
     * @param userId the user ID
     * @return list of all accounts
     */
    List<Account> findByUserId(Long userId);

    /**
     * Find accounts by status.
     *
     * Uses index on status column.
     *
     * @param status the account status
     * @return list of accounts with the specified status
     */
    List<Account> findByStatus(Account.AccountStatus status);

    /**
     * Count active accounts for a user.
     *
     * More efficient than loading all accounts and counting in memory.
     *
     * @param userId the user ID
     * @return count of active accounts
     */
    @Query("SELECT COUNT(a) FROM Account a WHERE a.user.id = :userId AND a.status = 'ACTIVE'")
    long countActiveAccountsByUserId(@Param("userId") Long userId);

    /**
     * Find account with user and account type eagerly loaded.
     *
     * Uses JOIN FETCH to avoid N+1 query problem.
     * Useful when you need account details including user and type info.
     *
     * @param id the account ID
     * @return Optional containing the account with relationships if found
     */
    @Query("SELECT a FROM Account a " +
           "LEFT JOIN FETCH a.user " +
           "LEFT JOIN FETCH a.accountType " +
           "WHERE a.id = :id")
    Optional<Account> findByIdWithDetails(@Param("id") Long id);
}
