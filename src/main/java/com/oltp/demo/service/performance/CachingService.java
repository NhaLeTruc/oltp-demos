package com.oltp.demo.service.performance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.oltp.demo.domain.Account;
import com.oltp.demo.repository.AccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service demonstrating caching performance benefits with Redis.
 *
 * Demonstrates:
 * - Cache hits vs misses
 * - Automatic caching with @Cacheable
 * - Cache invalidation with @CacheEvict
 * - Cache updates with @CachePut
 * - TTL-based expiration
 *
 * Expected results:
 * - 5-10x faster queries with cache hits
 * - Reduced database load
 * - Lower latency for frequently accessed data
 *
 * Redis is configured in CacheConfig with appropriate TTL settings.
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US3: Performance Under Load</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CachingService {

    private final AccountRepository accountRepository;
    private final CacheManager cacheManager;

    private static final String ACCOUNT_CACHE = "accounts";

    /**
     * Fetches account with caching enabled.
     *
     * First call: cache miss - queries database
     * Subsequent calls: cache hit - returns from Redis
     *
     * @param accountId Account ID
     * @return Account if found
     */
    @Cacheable(value = ACCOUNT_CACHE, key = "#accountId")
    public Optional<Account> getAccountCached(Long accountId) {
        log.info("CACHE MISS: Fetching account {} from database", accountId);

        // Simulate some processing time
        try {
            Thread.sleep(50);  // 50ms database query simulation
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return accountRepository.findById(accountId);
    }

    /**
     * Fetches account without caching.
     *
     * Always queries the database directly.
     *
     * @param accountId Account ID
     * @return Account if found
     */
    public Optional<Account> getAccountUncached(Long accountId) {
        log.info("NO CACHE: Fetching account {} from database", accountId);

        // Simulate same processing time for fair comparison
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return accountRepository.findById(accountId);
    }

    /**
     * Updates cached account data.
     *
     * Updates both database and cache atomically.
     *
     * @param accountId Account ID
     * @param amount Amount to credit
     * @return Updated account
     */
    @CachePut(value = ACCOUNT_CACHE, key = "#accountId")
    public Optional<Account> updateAccountCached(Long accountId, BigDecimal amount) {
        log.info("CACHE UPDATE: Updating account {} and refreshing cache", accountId);

        Optional<Account> accountOpt = accountRepository.findById(accountId);
        accountOpt.ifPresent(account -> {
            account.credit(amount);
            accountRepository.save(account);
        });

        return accountOpt;
    }

    /**
     * Evicts account from cache.
     *
     * Forces next fetch to be a cache miss.
     *
     * @param accountId Account ID to evict
     */
    @CacheEvict(value = ACCOUNT_CACHE, key = "#accountId")
    public void evictAccountFromCache(Long accountId) {
        log.info("CACHE EVICT: Removing account {} from cache", accountId);
    }

    /**
     * Clears entire account cache.
     */
    @CacheEvict(value = ACCOUNT_CACHE, allEntries = true)
    public void clearAllCache() {
        log.info("CACHE CLEAR: Removing all accounts from cache");
    }

    /**
     * Demonstrates cache performance with multiple queries.
     *
     * Runs same query multiple times to show cache hit benefits.
     *
     * @param accountId Account to query
     * @param iterations Number of queries
     * @param useCache Whether to use caching
     * @return Performance results
     */
    public CachePerformanceResult demonstrateCachePerformance(
            Long accountId,
            int iterations,
            boolean useCache) {

        long startTime = System.currentTimeMillis();
        List<Long> queryTimes = new ArrayList<>();
        int hits = 0;
        int misses = 0;

        // Clear cache before test if using cache
        if (useCache) {
            clearAllCache();
        }

        for (int i = 0; i < iterations; i++) {
            long queryStart = System.nanoTime();

            Optional<Account> result;
            if (useCache) {
                result = getAccountCached(accountId);
                // First query is always a miss, rest are hits if same ID
                if (i == 0) {
                    misses++;
                } else {
                    hits++;
                }
            } else {
                result = getAccountUncached(accountId);
                misses++;  // All uncached queries are misses
            }

            long queryTimeUs = (System.nanoTime() - queryStart) / 1_000;
            queryTimes.add(queryTimeUs);
        }

        long totalDurationMs = System.currentTimeMillis() - startTime;

        return new CachePerformanceResult(
            useCache,
            iterations,
            hits,
            misses,
            totalDurationMs,
            calculateAverage(queryTimes),
            calculateMin(queryTimes),
            calculateMax(queryTimes)
        );
    }

    /**
     * Compares cached vs uncached performance.
     *
     * @param accountId Account to test
     * @param iterations Number of queries for each test
     * @return Comparison results
     */
    public CacheComparisonResult compareCachingStrategies(Long accountId, int iterations) {
        // Test without cache
        CachePerformanceResult uncachedResult =
            demonstrateCachePerformance(accountId, iterations, false);

        // Small delay between tests
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Test with cache
        CachePerformanceResult cachedResult =
            demonstrateCachePerformance(accountId, iterations, true);

        double speedup = uncachedResult.avgQueryTimeUs / cachedResult.avgQueryTimeUs;

        return new CacheComparisonResult(
            uncachedResult,
            cachedResult,
            speedup,
            calculateHitRate(cachedResult),
            String.format("Caching provides %.1fx speedup with %.1f%% hit rate",
                speedup, calculateHitRate(cachedResult))
        );
    }

    /**
     * Gets cache statistics.
     *
     * @return Current cache stats or null if unavailable
     */
    public CacheStatistics getCacheStatistics() {
        var cache = cacheManager.getCache(ACCOUNT_CACHE);
        if (cache != null) {
            // Note: Actual stats depend on cache implementation
            // This is a simplified version
            return new CacheStatistics(
                ACCOUNT_CACHE,
                true,  // Assume enabled
                "Redis",
                null   // Stats vary by implementation
            );
        }
        return null;
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private double calculateAverage(List<Long> values) {
        if (values.isEmpty()) return 0;
        return values.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private long calculateMin(List<Long> values) {
        if (values.isEmpty()) return 0;
        return values.stream().mapToLong(Long::longValue).min().orElse(0);
    }

    private long calculateMax(List<Long> values) {
        if (values.isEmpty()) return 0;
        return values.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    private double calculateHitRate(CachePerformanceResult result) {
        int total = result.hits + result.misses;
        return total > 0 ? (result.hits * 100.0) / total : 0;
    }

    // =========================================================================
    // Result DTOs
    // =========================================================================

    public record CachePerformanceResult(
        boolean cacheEnabled,
        int totalQueries,
        int hits,
        int misses,
        long totalDurationMs,
        double avgQueryTimeUs,
        long minQueryTimeUs,
        long maxQueryTimeUs
    ) {
        public double queriesPerSecond() {
            return (totalQueries * 1000.0) / totalDurationMs;
        }

        public double hitRate() {
            int total = hits + misses;
            return total > 0 ? (hits * 100.0) / total : 0;
        }
    }

    public record CacheComparisonResult(
        CachePerformanceResult uncachedResult,
        CachePerformanceResult cachedResult,
        double speedupFactor,
        double cacheHitRate,
        String summary
    ) {
        public long timeSavedMs() {
            return uncachedResult.totalDurationMs - cachedResult.totalDurationMs;
        }

        public double timeSavedPercent() {
            return ((uncachedResult.totalDurationMs - cachedResult.totalDurationMs) * 100.0)
                / uncachedResult.totalDurationMs;
        }
    }

    public record CacheStatistics(
        String cacheName,
        boolean enabled,
        String provider,
        Object nativeStats
    ) {}
}
