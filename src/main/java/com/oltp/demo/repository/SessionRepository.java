package com.oltp.demo.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.oltp.demo.domain.Session;

/**
 * Repository for Session entity.
 *
 * Sessions are used for connection pooling demonstrations:
 * - High-volume INSERT operations (session creation)
 * - High-volume UPDATE operations (session access tracking)
 * - Batch DELETE operations (expired session cleanup)
 *
 * Performance targets (per constitution.md):
 * - Session creation: < 5ms (p95)
 * - Session lookup: < 5ms (p95)
 * - Batch cleanup: < 50ms for 1000 sessions (p95)
 *
 * Indexes used:
 * - idx_sessions_session_token: Fast lookup by token
 * - idx_sessions_user_id: User's sessions
 * - idx_sessions_expires_at: Cleanup queries
 * - idx_sessions_last_accessed: Activity tracking
 *
 * @see com.oltp.demo.service.performance.ConnectionPoolDemoService
 */
@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {

    /**
     * Find session by token.
     *
     * Primary lookup method for session validation.
     * Uses unique index: idx_sessions_session_token
     *
     * @param sessionToken the session token UUID
     * @return Optional containing the session if found
     */
    Optional<Session> findBySessionToken(UUID sessionToken);

    /**
     * Find all sessions for a user.
     *
     * Uses index: idx_sessions_user_id
     *
     * @param userId the user ID
     * @return list of all sessions for the user
     */
    List<Session> findByUserId(Long userId);

    /**
     * Find active (non-expired) sessions for a user.
     *
     * Uses partial index: idx_sessions_active (user_id, expires_at WHERE expires_at > NOW())
     *
     * @param userId the user ID
     * @param now current timestamp
     * @return list of active sessions
     */
    @Query("SELECT s FROM Session s WHERE s.user.id = :userId AND s.expiresAt > :now")
    List<Session> findActiveSessionsByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Find expired sessions (for cleanup).
     *
     * Uses index: idx_sessions_expires_at
     * Returns sessions that have passed their expiration time.
     *
     * @param now current timestamp
     * @return list of expired sessions
     */
    @Query("SELECT s FROM Session s WHERE s.expiresAt <= :now")
    List<Session> findExpiredSessions(@Param("now") Instant now);

    /**
     * Delete expired sessions in batch.
     *
     * Used for scheduled cleanup to prevent table bloat.
     * Demonstrates batch DELETE performance.
     *
     * Uses index: idx_sessions_expires_at
     *
     * @param now current timestamp
     * @return number of sessions deleted
     */
    @Modifying
    @Query("DELETE FROM Session s WHERE s.expiresAt <= :now")
    int deleteExpiredSessions(@Param("now") Instant now);

    /**
     * Find sessions not accessed recently (idle sessions).
     *
     * Uses index: idx_sessions_last_accessed
     *
     * @param threshold timestamp threshold for "idle"
     * @return list of idle sessions
     */
    @Query("SELECT s FROM Session s WHERE s.lastAccessedAt < :threshold")
    List<Session> findIdleSessions(@Param("threshold") Instant threshold);

    /**
     * Count active sessions for a user.
     *
     * More efficient than loading all sessions and counting in memory.
     *
     * @param userId the user ID
     * @param now current timestamp
     * @return count of active sessions
     */
    @Query("SELECT COUNT(s) FROM Session s WHERE s.user.id = :userId AND s.expiresAt > :now")
    long countActiveSessionsByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Check if session token exists and is not expired.
     *
     * More efficient than loading the full session entity.
     *
     * @param sessionToken the session token UUID
     * @param now current timestamp
     * @return true if valid session exists
     */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM Session s " +
           "WHERE s.sessionToken = :token AND s.expiresAt > :now")
    boolean isValidSession(@Param("token") UUID sessionToken, @Param("now") Instant now);
}
