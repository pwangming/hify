package com.hify.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * identity 模块配置（外化到 application.yml 的 {@code hify.identity.*}）。
 *
 * <p>{@code bootstrap-admin.username/password} 用于启动时引导首个 admin 账号；其值经 application.yml
 * 引用环境变量（{@code HIFY_ADMIN_USERNAME}/{@code HIFY_ADMIN_PASSWORD}），密码不写明文（CLAUDE.md 安全要点）。
 * 由 {@link AdminBootstrapRunner} 上的 {@code @EnableConfigurationProperties} 注册绑定。
 */
@ConfigurationProperties(prefix = "hify.identity.bootstrap-admin")
public record IdentityProperties(String username, String password) {
}
