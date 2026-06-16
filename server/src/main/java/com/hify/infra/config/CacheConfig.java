package com.hify.infra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 进程内缓存配置（一期用 Caffeine，不引 Redis；多副本扩容时再换共享缓存——见 scaling-path.md）。
 *
 * <p>{@code @EnableCaching} 打开 Spring 的缓存抽象，业务侧用 {@code @Cacheable} 等注解即可，
 * 底层换实现不影响业务代码。
 *
 * <p>缓存参数<b>外化到 application.yml</b>（CLAUDE.md：配置不硬编码），用 Caffeine 的 spec 字符串
 * 一行表达（如 {@code maximumSize=10000,expireAfterWrite=30m}），改容量/过期无需动代码。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Caffeine 规格串，来自 application.yml 的 hify.cache.spec。 */
    @Value("${hify.cache.spec}")
    private String cacheSpec;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCacheSpecification(cacheSpec);
        return cacheManager;
    }
}
