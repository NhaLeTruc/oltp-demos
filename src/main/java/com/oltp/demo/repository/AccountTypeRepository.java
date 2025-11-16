package com.oltp.demo.repository;

import java.util.Optional;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.oltp.demo.domain.AccountType;

/**
 * Repository for AccountType entity.
 *
 * AccountType is reference data that changes infrequently,
 * making it an excellent candidate for caching.
 *
 * All find methods are annotated with @Cacheable to leverage
 * Redis cache configured in CacheConfig.
 *
 * Cache TTL: 1 hour (configured in CacheConfig)
 *
 * @see com.oltp.demo.config.CacheConfig
 */
@Repository
public interface AccountTypeRepository extends JpaRepository<AccountType, Integer> {

    /**
     * Find account type by name with caching.
     *
     * Cache key: "accountTypes::{typeName}"
     * TTL: 1 hour
     *
     * @param typeName the account type name (e.g., "CHECKING", "SAVINGS")
     * @return Optional containing the account type if found
     */
    @Cacheable(value = "accountTypes", key = "#typeName")
    Optional<AccountType> findByTypeName(String typeName);

    /**
     * Check if account type exists by name.
     *
     * This is more efficient than findByTypeName when you only need
     * existence check (doesn't load the entity).
     *
     * @param typeName the account type name
     * @return true if account type exists
     */
    boolean existsByTypeName(String typeName);
}
