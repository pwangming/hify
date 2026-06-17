package com.hify.infra.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 总装配（infra 安全管道）。把 JWT 过滤器、认证入口、权限处理器接成一条
 * 无状态的 SecurityFilterChain，并落地 api-standards.md 第 1 节的路由三族鉴权规则。
 *
 * <p>关键决策：
 * <ul>
 *   <li><b>无状态（STATELESS）</b>：不创建/依赖 HttpSession，身份完全由每个请求自带的 JWT 证明，
 *       契合前后端分离 + 可水平扩容（scaling-path.md：副本间不共享 session）。</li>
 *   <li><b>关 CSRF / formLogin / httpBasic</b>：纯 Token 化 REST API，用不到表单登录与浏览器
 *       会话级 CSRF 防护。</li>
 *   <li><b>放行清单</b>：健康检查、actuator 探活、（identity 模块后续落地的）登录接口、对外 API
 *       （{@code /v1/apps/**} 由后续 App Key 过滤器自行鉴权，不套 JWT）。</li>
 *   <li><b>admin 路由要 ADMIN 角色</b>：{@code hasRole("ADMIN")} 对应过滤器里写入的
 *       {@code ROLE_ADMIN} 权限。</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            SecurityResponseWriter responseWriter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        // identity 模块落地登录接口后即生效；现在先放行，避免将来改安全配置
                        .requestMatchers("/api/v1/identity/login").permitAll()
                        // 对外 API：身份由 App API Key 证明，后续在该前缀加独立过滤器，这里不套 JWT
                        .requestMatchers("/v1/apps/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, responseWriter),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }
}
