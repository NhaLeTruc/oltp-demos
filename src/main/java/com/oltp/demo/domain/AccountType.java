package com.oltp.demo.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Account Type entity representing different types of financial accounts.
 *
 * This is reference data that changes infrequently and is an excellent
 * candidate for caching (see CacheConfig.java).
 *
 * Account types define business rules like minimum balance requirements:
 * - CHECKING: $0 minimum
 * - SAVINGS: $100 minimum
 * - BUSINESS: $1000 minimum
 * - PREMIUM: $5000 minimum
 *
 * @see com.oltp.demo.config.CacheConfig
 */
@Entity
@Table(name = "account_types")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class AccountType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /**
     * Unique name of the account type (e.g., "CHECKING", "SAVINGS").
     */
    @Column(nullable = false, unique = true, name = "type_name", length = 50)
    private String typeName;

    /**
     * Minimum balance requirement for this account type.
     * Enforced via database CHECK constraint and application logic.
     */
    @Column(nullable = false, name = "min_balance", precision = 15, scale = 2)
    private BigDecimal minBalance;

    /**
     * Human-readable description of the account type.
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Timestamp when this account type was created.
     * Immutable (updatable = false).
     */
    @Column(nullable = false, updatable = false, name = "created_at")
    private Instant createdAt;

    /**
     * Sets createdAt timestamp before persisting.
     */
    @jakarta.persistence.PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
