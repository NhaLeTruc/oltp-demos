package com.oltp.demo.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Session entity for tracking active user sessions.
 *
 * Used primarily for connection pooling demonstrations:
 * - High-volume INSERT/UPDATE operations to stress connection pool
 * - Session cleanup queries to demonstrate batch DELETE performance
 * - Concurrent session access patterns
 *
 * Not used for actual authentication in this demo (see SecurityConfig).
 *
 * @see com.oltp.demo.service.performance.ConnectionPoolDemoService
 * @see com.oltp.demo.config.SecurityConfig
 */
@Entity
@Table(name = "sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "user")
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * User who owns this session.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Unique session token (UUID).
     */
    @Column(nullable = false, unique = true, name = "session_token")
    private UUID sessionToken;

    /**
     * Timestamp when session was created.
     * Immutable (updatable = false).
     */
    @Column(nullable = false, updatable = false, name = "created_at")
    private Instant createdAt;

    /**
     * Timestamp when session expires.
     * Used for cleanup queries: DELETE FROM sessions WHERE expires_at < NOW()
     */
    @Column(nullable = false, name = "expires_at")
    private Instant expiresAt;

    /**
     * Timestamp when session was last accessed.
     * Updated on every session access to demonstrate UPDATE throughput.
     */
    @Column(nullable = false, name = "last_accessed_at")
    private Instant lastAccessedAt;

    /**
     * Sets timestamps and generates session token before persisting.
     */
    @jakarta.persistence.PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.lastAccessedAt = now;

        if (this.sessionToken == null) {
            this.sessionToken = UUID.randomUUID();
        }

        // Default expiration: 24 hours from creation
        if (this.expiresAt == null) {
            this.expiresAt = now.plusSeconds(86400);  // 24 hours
        }
    }

    /**
     * Updates last accessed timestamp.
     */
    public void touch() {
        this.lastAccessedAt = Instant.now();
    }

    /**
     * Checks if session is expired.
     *
     * @return true if current time is after expiration time
     */
    public boolean isExpired() {
        return Instant.now().isAfter(this.expiresAt);
    }

    /**
     * Extends session expiration by specified seconds.
     *
     * @param seconds number of seconds to extend expiration
     */
    public void extendExpiration(long seconds) {
        this.expiresAt = Instant.now().plusSeconds(seconds);
    }
}
