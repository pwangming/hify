package com.hify.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 相关配置（外化到 application.yml 的 {@code hify.security.jwt.*}，CLAUDE.md 行为指令：
 * 配置不硬编码；密钥这类敏感项走 {@code .env} 环境变量引用）。
 *
 * <p>由 {@link SecurityConfig} 上的 {@code @EnableConfigurationProperties} 注册并完成绑定。
 */
@ConfigurationProperties(prefix = "hify.security.jwt")
public class JwtProperties {

    /**
     * HMAC-SHA256 签名密钥。<b>至少 32 字节（256 位）</b>，否则 jjwt 拒绝签发。
     * 生产环境必须经 {@code .env} 的 {@code HIFY_JWT_SECRET} 覆盖，禁止用代码里的开发默认值。
     */
    private String secret;

    /** 令牌有效期（分钟）。一期单令牌、无刷新令牌机制。 */
    private long expireMinutes = 1440;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpireMinutes() {
        return expireMinutes;
    }

    public void setExpireMinutes(long expireMinutes) {
        this.expireMinutes = expireMinutes;
    }
}
