package com.hify.usage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * usage 模块配置（hify.usage.*）。
 * daily-token-limit-per-user：单用户每日 Token 上限（prompt+completion 累计），入口配额检查据此拦截。
 * 由 {@link UsageConfig} 上的 {@code @EnableConfigurationProperties} 注册绑定。
 */
@ConfigurationProperties(prefix = "hify.usage")
public record UsageProperties(long dailyTokenLimitPerUser) {
}
