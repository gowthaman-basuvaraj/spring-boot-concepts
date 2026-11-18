package com.example.orders.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.cache.CacheManagerCustomizer;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caching.
 *
 * <p>Three things people get wrong with {@code @Cacheable}:
 * <ol>
 *   <li><b>Self-invocation.</b> Like {@code @Transactional}, it is proxy-based. A private or
 *       internally-called method is never cached.</li>
 *   <li><b>No TTL.</b> {@code ConcurrentMapCacheManager} (Boot's default when no provider is on the
 *       classpath) never evicts. Caffeine with an explicit {@code expireAfterWrite} is the minimum
 *       bar; without it you are building a memory leak with a business justification.</li>
 *   <li><b>Caching inside a transaction, evicting outside it.</b> {@code @CacheEvict} runs when the
 *       method returns, not when the transaction commits, so a rollback leaves the cache correct but
 *       a concurrent reader may have re-populated it from uncommitted-then-rolled-back state. For
 *       strict cases evict after commit (see {@code OrderEventListeners}).</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
public class CacheConfig {

    public static final String ORDER_BY_NUMBER = "orderByNumber";
    public static final String CUSTOMER_LTV = "customerLifetimeValue";

    @Bean
    CacheManagerCustomizer<CaffeineCacheManager> cacheManagerCustomizer() {
        return cacheManager -> {
            cacheManager.setCacheNames(java.util.List.of(ORDER_BY_NUMBER, CUSTOMER_LTV));
            cacheManager.setCaffeine(Caffeine.newBuilder()
                    .maximumSize(10_000)
                    .expireAfterWrite(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS)
                    .recordStats()); // exported as cache.* metrics by Boot
            // Do not cache nulls: a 404 cached for 5 minutes is a support ticket.
            cacheManager.setAllowNullValues(false);
        };
    }
}
