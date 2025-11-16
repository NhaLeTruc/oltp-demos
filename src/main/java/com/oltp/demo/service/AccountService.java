package com.oltp.demo.service;

import com.oltp.demo.domain.Account;
import com.oltp.demo.domain.AccountType;
import com.oltp.demo.domain.User;
import com.oltp.demo.repository.AccountRepository;
import com.oltp.demo.repository.AccountTypeRepository;
import com.oltp.demo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing accounts.
 * Provides CRUD operations and query capabilities for demonstration purposes.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountTypeRepository accountTypeRepository;

    public AccountService(
            AccountRepository accountRepository,
            UserRepository userRepository,
            AccountTypeRepository accountTypeRepository
    ) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.accountTypeRepository = accountTypeRepository;
    }

    /**
     * Create a new account.
     *
     * @param userId User ID
     * @param accountTypeId Account type ID
     * @param initialBalance Initial balance
     * @param accountNumber Optional account number (generated if not provided)
     * @return Created account
     */
    @Transactional
    public Account createAccount(
            Long userId,
            Long accountTypeId,
            BigDecimal initialBalance,
            String accountNumber
    ) {
        log.info("Creating account: userId={}, accountTypeId={}, initialBalance={}",
                userId, accountTypeId, initialBalance);

        // Validate user exists
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // Validate account type exists
        AccountType accountType = accountTypeRepository.findById(accountTypeId)
                .orElseThrow(() -> new IllegalArgumentException("Account type not found: " + accountTypeId));

        // Validate initial balance
        if (initialBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Initial balance cannot be negative");
        }

        // Generate account number if not provided
        String finalAccountNumber = accountNumber;
        if (finalAccountNumber == null || finalAccountNumber.isEmpty()) {
            finalAccountNumber = generateAccountNumber();
        }

        // Create account
        Account account = new Account();
        account.setUser(user);
        account.setAccountType(accountType);
        account.setAccountNumber(finalAccountNumber);
        account.setBalance(initialBalance);
        account.setStatus("ACTIVE");
        account.setVersion(0L);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());

        Account saved = accountRepository.save(account);
        log.info("Account created: id={}, accountNumber={}", saved.getId(), saved.getAccountNumber());

        return saved;
    }

    /**
     * Get account by ID.
     *
     * @param accountId Account ID
     * @return Account if found
     */
    @Transactional(readOnly = true)
    public Optional<Account> getAccount(Long accountId) {
        log.debug("Getting account: id={}", accountId);
        return accountRepository.findById(accountId);
    }

    /**
     * List accounts with optional filtering.
     *
     * @param userId Optional user ID filter
     * @param accountTypeId Optional account type ID filter
     * @param status Optional status filter
     * @param pageable Pagination and sorting
     * @return Page of accounts
     */
    @Transactional(readOnly = true)
    public Page<Account> listAccounts(
            Long userId,
            Long accountTypeId,
            String status,
            Pageable pageable
    ) {
        log.debug("Listing accounts: userId={}, accountTypeId={}, status={}",
                userId, accountTypeId, status);

        Specification<Account> spec = Specification.where(null);

        if (userId != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("user").get("id"), userId));
        }

        if (accountTypeId != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("accountType").get("id"), accountTypeId));
        }

        if (status != null && !status.isEmpty()) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("status"), status));
        }

        return accountRepository.findAll(spec, pageable);
    }

    /**
     * List all accounts (no filtering).
     *
     * @param pageable Pagination and sorting
     * @return Page of accounts
     */
    @Transactional(readOnly = true)
    public Page<Account> listAllAccounts(Pageable pageable) {
        log.debug("Listing all accounts");
        return accountRepository.findAll(pageable);
    }

    /**
     * Update account status.
     *
     * @param accountId Account ID
     * @param status New status
     * @return Updated account
     */
    @Transactional
    public Account updateAccountStatus(Long accountId, String status) {
        log.info("Updating account status: id={}, status={}", accountId, status);

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        account.setStatus(status);
        account.setUpdatedAt(Instant.now());

        return accountRepository.save(account);
    }

    /**
     * Update account balance.
     * Note: This is for testing/demo purposes only. In production, use transfer operations.
     *
     * @param accountId Account ID
     * @param newBalance New balance
     * @return Updated account
     */
    @Transactional
    public Account updateAccountBalance(Long accountId, BigDecimal newBalance) {
        log.warn("Updating account balance directly (demo/test only): id={}, newBalance={}",
                accountId, newBalance);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Balance cannot be negative");
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        account.setBalance(newBalance);
        account.setUpdatedAt(Instant.now());

        return accountRepository.save(account);
    }

    /**
     * Delete account.
     * Note: This is for testing/demo purposes only.
     *
     * @param accountId Account ID
     */
    @Transactional
    public void deleteAccount(Long accountId) {
        log.warn("Deleting account (demo/test only): id={}", accountId);

        if (!accountRepository.existsById(accountId)) {
            throw new IllegalArgumentException("Account not found: " + accountId);
        }

        accountRepository.deleteById(accountId);
        log.info("Account deleted: id={}", accountId);
    }

    /**
     * Count accounts by status.
     *
     * @param status Account status
     * @return Number of accounts with given status
     */
    @Transactional(readOnly = true)
    public long countAccountsByStatus(String status) {
        return accountRepository.count((root, query, cb) ->
                cb.equal(root.get("status"), status));
    }

    /**
     * Get accounts by user ID.
     *
     * @param userId User ID
     * @return List of accounts for user
     */
    @Transactional(readOnly = true)
    public List<Account> getAccountsByUser(Long userId) {
        log.debug("Getting accounts for user: userId={}", userId);

        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User not found: " + userId);
        }

        return accountRepository.findAll((root, query, cb) ->
                cb.equal(root.get("user").get("id"), userId));
    }

    /**
     * Generate a unique account number.
     *
     * @return Generated account number
     */
    private String generateAccountNumber() {
        // Simple sequential generation - in production, use more sophisticated logic
        long count = accountRepository.count();
        return String.format("ACC%012d", count + 1);
    }
}
