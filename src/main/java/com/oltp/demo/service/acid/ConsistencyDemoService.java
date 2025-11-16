package com.oltp.demo.service.acid;

import java.math.BigDecimal;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oltp.demo.domain.Account;
import com.oltp.demo.domain.AccountType;
import com.oltp.demo.domain.User;
import com.oltp.demo.repository.AccountRepository;
import com.oltp.demo.repository.AccountTypeRepository;
import com.oltp.demo.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating ACID Consistency property.
 *
 * Consistency: Database integrity constraints are never violated.
 * The database moves from one valid state to another valid state.
 *
 * Demonstrations:
 * 1. Balance constraint: Prevents negative balances (CHECK constraint)
 * 2. Foreign key constraint: Prevents orphaned accounts
 * 3. Minimum balance constraint: Enforces account type rules
 * 4. Unique constraint: Prevents duplicate usernames/emails
 *
 * Key mechanisms:
 * - Database CHECK constraints (chk_accounts_balance_non_negative)
 * - Foreign key constraints (fk_accounts_user, fk_accounts_type)
 * - Application-level validation
 * - Transaction rollback on constraint violation
 *
 * Constitution.md alignment:
 * - Principle I: Data Integrity First
 * - Constraint enforcement at database level
 * - Fail-fast on invariant violations
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US1: ACID Transaction Guarantees</a>
 * @see <a href="src/main/resources/db/migration/V5__add_constraints.sql">Database Constraints</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsistencyDemoService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountTypeRepository accountTypeRepository;

    /**
     * Demonstrates negative balance constraint violation.
     *
     * Attempts to withdraw more than the account balance.
     * Database CHECK constraint prevents negative balance:
     * - chk_accounts_balance_non_negative CHECK (balance >= 0)
     *
     * Expected behavior:
     * - DataIntegrityViolationException thrown
     * - Transaction rolled back automatically
     * - Account balance unchanged
     *
     * @param accountId the account to withdraw from
     * @param withdrawalAmount amount to withdraw (should exceed balance)
     * @return constraint validation result
     */
    @Transactional
    public ConstraintViolationResult demonstrateNegativeBalanceConstraint(
            Long accountId,
            BigDecimal withdrawalAmount) {

        log.info("Demonstrating negative balance constraint: account={}, withdrawal={}",
                accountId, withdrawalAmount);

        try {
            Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));

            BigDecimal originalBalance = account.getBalance();
            log.info("Original balance: {}", originalBalance);

            // Attempt to set negative balance (violates CHECK constraint)
            // This will throw DataIntegrityViolationException
            account.setBalance(originalBalance.subtract(withdrawalAmount));
            accountRepository.save(account);

            // Flush to trigger constraint check immediately
            accountRepository.flush();

            // Should not reach here
            return ConstraintViolationResult.failure("Constraint should have been violated!");

        } catch (DataIntegrityViolationException e) {
            log.info("✓ Constraint enforced: {}", e.getMessage());
            return ConstraintViolationResult.success(
                "Negative balance constraint enforced",
                "chk_accounts_balance_non_negative"
            );
        } catch (IllegalArgumentException e) {
            // This would happen if trying to use account.debit() which has its own validation
            log.info("✓ Application validation enforced: {}", e.getMessage());
            return ConstraintViolationResult.success(
                "Application-level validation enforced",
                "Account.debit()"
            );
        }
    }

    /**
     * Demonstrates foreign key constraint violation.
     *
     * Attempts to create an account with non-existent user.
     * Database FK constraint prevents orphaned accounts:
     * - fk_accounts_user FOREIGN KEY (user_id) REFERENCES users(id)
     *
     * Expected behavior:
     * - DataIntegrityViolationException thrown
     * - Transaction rolled back
     * - No orphaned account created
     *
     * @param nonExistentUserId user ID that doesn't exist
     * @param accountTypeId valid account type ID
     * @return constraint validation result
     */
    @Transactional
    public ConstraintViolationResult demonstrateForeignKeyConstraint(
            Long nonExistentUserId,
            Integer accountTypeId) {

        log.info("Demonstrating foreign key constraint: userId={}, typeId={}",
                nonExistentUserId, accountTypeId);

        try {
            // Verify user doesn't exist
            if (userRepository.existsById(nonExistentUserId)) {
                return ConstraintViolationResult.failure("User exists, cannot demonstrate constraint");
            }

            // Get account type
            AccountType accountType = accountTypeRepository.findById(accountTypeId)
                .orElseThrow(() -> new IllegalArgumentException("Account type not found"));

            // Create account with non-existent user (violates FK constraint)
            // We need to create a detached User entity to bypass JPA checks
            User nonExistentUser = new User();
            nonExistentUser.setId(nonExistentUserId);

            Account account = Account.builder()
                .user(nonExistentUser)
                .accountType(accountType)
                .balance(BigDecimal.valueOf(100.00))
                .status(Account.AccountStatus.ACTIVE)
                .build();

            accountRepository.save(account);
            accountRepository.flush();

            // Should not reach here
            return ConstraintViolationResult.failure("FK constraint should have been violated!");

        } catch (DataIntegrityViolationException e) {
            log.info("✓ Foreign key constraint enforced: {}", e.getMessage());
            return ConstraintViolationResult.success(
                "Foreign key constraint enforced - cannot create account for non-existent user",
                "fk_accounts_user"
            );
        }
    }

    /**
     * Demonstrates minimum balance constraint violation.
     *
     * Attempts to reduce balance below the minimum required for account type.
     * Database CHECK constraint enforces minimum balance rules:
     * - chk_accounts_balance_respects_minimum
     *
     * Account type minimums (from seed data):
     * - CHECKING: $0
     * - SAVINGS: $100
     * - BUSINESS: $1000
     * - PREMIUM: $5000
     *
     * @param accountId account ID (should be SAVINGS or higher)
     * @param targetBalance balance to set (below minimum)
     * @return constraint validation result
     */
    @Transactional
    public ConstraintViolationResult demonstrateMinimumBalanceConstraint(
            Long accountId,
            BigDecimal targetBalance) {

        log.info("Demonstrating minimum balance constraint: account={}, target={}",
                accountId, targetBalance);

        try {
            Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));

            AccountType accountType = account.getAccountType();
            BigDecimal minBalance = accountType.getMinBalance();

            log.info("Account type: {}, minimum balance: {}", accountType.getTypeName(), minBalance);

            if (targetBalance.compareTo(minBalance) >= 0) {
                return ConstraintViolationResult.failure(
                    String.format("Target balance %s is not below minimum %s", targetBalance, minBalance)
                );
            }

            // Attempt to set balance below minimum (violates CHECK constraint)
            account.setBalance(targetBalance);
            accountRepository.save(account);
            accountRepository.flush();

            // Should not reach here
            return ConstraintViolationResult.failure("Minimum balance constraint should have been violated!");

        } catch (DataIntegrityViolationException e) {
            log.info("✓ Minimum balance constraint enforced: {}", e.getMessage());
            return ConstraintViolationResult.success(
                "Minimum balance constraint enforced",
                "chk_accounts_balance_respects_minimum"
            );
        }
    }

    /**
     * Demonstrates unique constraint violation.
     *
     * Attempts to create user with duplicate username.
     * Database UNIQUE constraint prevents duplicates:
     * - username column has UNIQUE constraint
     *
     * Expected behavior:
     * - DataIntegrityViolationException thrown
     * - Transaction rolled back
     * - No duplicate user created
     *
     * @param existingUsername username that already exists
     * @return constraint validation result
     */
    @Transactional
    public ConstraintViolationResult demonstrateUniqueConstraint(String existingUsername) {
        log.info("Demonstrating unique constraint: username={}", existingUsername);

        try {
            // Verify username exists
            if (!userRepository.existsByUsername(existingUsername)) {
                return ConstraintViolationResult.failure("Username doesn't exist, cannot demonstrate constraint");
            }

            // Attempt to create user with duplicate username
            User duplicateUser = User.builder()
                .username(existingUsername)
                .email("different_" + System.currentTimeMillis() + "@example.com")
                .fullName("Duplicate User")
                .build();

            userRepository.save(duplicateUser);
            userRepository.flush();

            // Should not reach here
            return ConstraintViolationResult.failure("Unique constraint should have been violated!");

        } catch (DataIntegrityViolationException e) {
            log.info("✓ Unique constraint enforced: {}", e.getMessage());
            return ConstraintViolationResult.success(
                "Unique constraint enforced - duplicate username rejected",
                "users.username UNIQUE"
            );
        }
    }

    /**
     * Verifies that valid operations maintain consistency.
     *
     * Demonstrates that transactions within valid constraints succeed.
     *
     * @param accountId account to operate on
     * @param amount valid amount to withdraw
     * @return validation result
     */
    @Transactional
    public ConstraintViolationResult demonstrateValidOperation(Long accountId, BigDecimal amount) {
        log.info("Demonstrating valid operation: account={}, amount={}", accountId, amount);

        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        BigDecimal originalBalance = account.getBalance();
        AccountType accountType = account.getAccountType();
        BigDecimal minBalance = accountType.getMinBalance();

        // Check if operation is valid
        BigDecimal newBalance = originalBalance.subtract(amount);
        if (newBalance.compareTo(minBalance) < 0) {
            return ConstraintViolationResult.failure(
                String.format("Operation would violate minimum balance: %s < %s", newBalance, minBalance)
            );
        }

        // Perform valid operation
        account.setBalance(newBalance);
        accountRepository.save(account);

        log.info("✓ Valid operation succeeded: balance {} → {}", originalBalance, newBalance);

        return ConstraintViolationResult.success(
            String.format("Valid operation succeeded: balance %s → %s", originalBalance, newBalance),
            "No constraints violated"
        );
    }

    /**
     * Constraint violation result DTO.
     */
    public record ConstraintViolationResult(
        boolean constraintEnforced,
        String message,
        String constraintName
    ) {
        public static ConstraintViolationResult success(String message, String constraintName) {
            return new ConstraintViolationResult(true, message, constraintName);
        }

        public static ConstraintViolationResult failure(String message) {
            return new ConstraintViolationResult(false, message, null);
        }
    }
}
