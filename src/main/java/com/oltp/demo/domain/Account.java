package com.oltp.demo.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Account entity representing financial accounts with balances.
 *
 * Core entity for ACID and concurrency demonstrations.
 *
 * Key features:
 * - Optimistic locking via @Version for concurrent balance updates
 * - Balance constraint: must be >= 0 (enforced by database CHECK)
 * - Must respect minimum balance from account type
 * - Lazy loading of relationships for performance
 *
 * Concurrency strategies demonstrated:
 * - Optimistic locking: JPA @Version for high-contention scenarios
 * - Pessimistic locking: Explicit SELECT FOR UPDATE in repositories
 *
 * @see com.oltp.demo.repository.AccountRepository
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"user", "accountType"})  // Avoid circular references
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * User who owns this account.
     * Lazy loading for performance.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Type of account (CHECKING, SAVINGS, BUSINESS, PREMIUM).
     * Lazy loading for performance.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_type_id", nullable = false)
    private AccountType accountType;

    /**
     * Current account balance.
     * Must be non-negative (enforced by database CHECK constraint).
     * Must respect minimum balance from account type.
     */
    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    /**
     * Optimistic locking version.
     *
     * JPA automatically increments this on every update.
     * If two transactions try to update the same account concurrently,
     * the second one will fail with OptimisticLockException.
     *
     * This is key to demonstrating ACID isolation and concurrency control.
     */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    /**
     * Account status: ACTIVE, SUSPENDED, or CLOSED.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AccountStatus status = AccountStatus.ACTIVE;

    /**
     * Timestamp when account was created.
     * Immutable (updatable = false).
     */
    @Column(nullable = false, updatable = false, name = "created_at")
    private Instant createdAt;

    /**
     * Timestamp when account was last updated.
     */
    @Column(nullable = false, name = "updated_at")
    private Instant updatedAt;

    /**
     * Sets timestamps before persisting.
     */
    @jakarta.persistence.PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Updates the updatedAt timestamp before updating.
     */
    @jakarta.persistence.PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Account status enumeration.
     */
    public enum AccountStatus {
        ACTIVE,
        SUSPENDED,
        CLOSED
    }

    /**
     * Adds to the account balance.
     *
     * @param amount amount to add (must be positive)
     * @throws IllegalArgumentException if amount is negative or zero
     */
    public void credit(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
        this.balance = this.balance.add(amount);
    }

    /**
     * Subtracts from the account balance.
     *
     * @param amount amount to subtract (must be positive)
     * @throws IllegalArgumentException if amount is negative, zero, or exceeds balance
     */
    public void debit(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Debit amount must be positive");
        }
        if (this.balance.compareTo(amount) < 0) {
            throw new IllegalArgumentException(
                String.format("Insufficient funds: balance=%s, debit=%s", this.balance, amount)
            );
        }
        this.balance = this.balance.subtract(amount);
    }

    /**
     * Checks if account has sufficient balance for a debit.
     *
     * @param amount amount to check
     * @return true if balance >= amount
     */
    public boolean hasSufficientBalance(BigDecimal amount) {
        return this.balance.compareTo(amount) >= 0;
    }
}
