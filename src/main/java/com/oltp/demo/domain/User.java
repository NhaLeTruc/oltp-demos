package com.oltp.demo.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * User entity representing system users who own accounts.
 *
 * Users can have multiple accounts of different types.
 * Validation rules:
 * - Username: 3-50 characters, unique
 * - Email: Valid format, unique (enforced by database constraint)
 *
 * Concurrency: No optimistic locking (users rarely updated concurrently)
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "accounts")  // Avoid circular references
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unique username (3-50 characters).
     * Validated by database CHECK constraint: LENGTH(username) >= 3
     */
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /**
     * Unique email address.
     * Validated by database CHECK constraint for email format.
     */
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    /**
     * User's full name.
     */
    @Column(nullable = false, name = "full_name", length = 255)
    private String fullName;

    /**
     * Timestamp when user was created.
     * Immutable (updatable = false).
     */
    @Column(nullable = false, updatable = false, name = "created_at")
    private Instant createdAt;

    /**
     * Timestamp when user was last updated.
     */
    @Column(nullable = false, name = "updated_at")
    private Instant updatedAt;

    /**
     * Accounts owned by this user.
     * One user can have many accounts.
     */
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<Account> accounts = new HashSet<>();

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
     * Helper method to add an account to this user.
     *
     * @param account the account to add
     */
    public void addAccount(Account account) {
        accounts.add(account);
        account.setUser(this);
    }

    /**
     * Helper method to remove an account from this user.
     *
     * @param account the account to remove
     */
    public void removeAccount(Account account) {
        accounts.remove(account);
        account.setUser(null);
    }
}
