package com.oltp.demo.repository;

import java.util.Optional;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.oltp.demo.domain.User;

/**
 * Repository for User entity.
 *
 * Provides methods for finding users by various criteria.
 * Selected methods are cached for performance.
 *
 * Cache TTL: 10 minutes (configured in CacheConfig)
 *
 * @see com.oltp.demo.config.CacheConfig
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Find user by username with caching.
     *
     * Cache key: "users::{username}"
     * TTL: 10 minutes
     *
     * @param username the username to search for
     * @return Optional containing the user if found
     */
    @Cacheable(value = "users", key = "#username")
    Optional<User> findByUsername(String username);

    /**
     * Find user by email with caching.
     *
     * Cache key: "users::{email}"
     * TTL: 10 minutes
     *
     * @param email the email to search for
     * @return Optional containing the user if found
     */
    @Cacheable(value = "users", key = "#email")
    Optional<User> findByEmail(String email);

    /**
     * Check if username is already taken.
     *
     * More efficient than findByUsername when you only need existence check.
     *
     * @param username the username to check
     * @return true if username exists
     */
    boolean existsByUsername(String username);

    /**
     * Check if email is already taken.
     *
     * More efficient than findByEmail when you only need existence check.
     *
     * @param email the email to check
     * @return true if email exists
     */
    boolean existsByEmail(String email);

    /**
     * Find user with their accounts eagerly loaded.
     *
     * Uses JOIN FETCH to avoid N+1 query problem.
     * Not cached due to relationship complexity.
     *
     * @param username the username to search for
     * @return Optional containing the user with accounts if found
     */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.accounts WHERE u.username = :username")
    Optional<User> findByUsernameWithAccounts(@Param("username") String username);
}
