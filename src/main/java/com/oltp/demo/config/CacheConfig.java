package com.oltp.demo.config;

import java.time.Duration;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import lombok.extern.slf4j.Slf4j;

/**
 * Cache configuration for OLTP demo.
 *
 * Implements constitution.md principle II: "Performance Through Design"
 * - Redis-backed distributed caching
 * - Configurable TTL per cache
 * - JSON serialization for readability
 * - Cache invalidation support
 *
 * Caching strategy:
 * - accountTypes: Reference data, long TTL (1 hour)
 * - users: User profiles, medium TTL (10 minutes)
 * - Default: 10 minutes TTL
 *
 * Cache warming is performed on startup for reference data
 * (configurable via oltp.demo.performance.cache-warmup-enabled)
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Default Redis cache configuration.
     *
     * Configuration:
     * - TTL: 10 minutes (600 seconds)
     * - Key serialization: String
     * - Value serialization: JSON (for debugging and readability)
     * - Null value caching: Disabled (to prevent caching of lookup failures)
     *
     * @return default RedisCacheConfiguration
     */
    @Bean
    public RedisCacheConfiguration defaultCacheConfiguration() {
        log.info("Configuring default Redis cache: TTL=10m, JSON serialization");

        return RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .disableCachingNullValues()
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new StringRedisSerializer()
                )
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new GenericJackson2JsonRedisSerializer()
                )
            );
    }

    /**
     * Customizes cache configurations per cache name.
     *
     * Cache-specific TTLs:
     * - "accountTypes": 1 hour (reference data changes infrequently)
     * - "users": 10 minutes (balance between freshness and performance)
     * - "accounts": 5 minutes (balance data may change frequently)
     * - "transactions": 15 minutes (immutable after creation)
     *
     * @return RedisCacheManagerBuilderCustomizer
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return builder -> builder
            .withCacheConfiguration("accountTypes",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofHours(1))
                    .disableCachingNullValues()
                    .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                    .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
            )
            .withCacheConfiguration("users",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofMinutes(10))
                    .disableCachingNullValues()
                    .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                    .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
            )
            .withCacheConfiguration("accounts",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofMinutes(5))
                    .disableCachingNullValues()
                    .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                    .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
            )
            .withCacheConfiguration("transactions",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofMinutes(15))
                    .disableCachingNullValues()
                    .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                    .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
            );
    }
}
