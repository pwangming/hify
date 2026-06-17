package com.hify.infra.security;

import com.hify.common.exception.BizException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 验票过滤器——安检流水线上的验票员。每个请求过一次（{@link OncePerRequestFilter}）。
 *
 * <p>三种走向：
 * <ol>
 *   <li><b>没带 Bearer 令牌</b>：不在这里拦截，直接放行到后续授权决策——放行路由（如
 *       {@code /api/v1/health}）正常通过，受保护路由则由 EntryPoint 统一返回 401。
 *       这样"未认证"的判定只在一处（EntryPoint），过滤器不重复造 401。</li>
 *   <li><b>令牌有效</b>：解析出 {@link CurrentUser} 放进安全上下文，并带上角色权限
 *       （{@code admin} → {@code ROLE_ADMIN}），供后续 {@code hasRole} 决策。</li>
 *   <li><b>令牌无效/过期</b>：带了票但票是坏的，立即写 401 信封（区分 10002/10003）并终止链，
 *       不再往下走。</li>
 * </ol>
 *
 * <p>本类<b>不加 {@code @Component}</b>：由 {@link SecurityConfig} 手动 new 进安全过滤链。
 * 若标注 @Component，Spring Boot 会把它额外注册到主 Servlet 过滤链上，导致对所有请求重复执行。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final SecurityResponseWriter responseWriter;

    public JwtAuthenticationFilter(JwtService jwtService, SecurityResponseWriter responseWriter) {
        this.jwtService = jwtService;
        this.responseWriter = responseWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AUTH_HEADER);
        if (!StringUtils.hasText(header) || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());
        try {
            CurrentUser user = jwtService.parseToken(token);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role().toUpperCase())));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (BizException ex) {
            SecurityContextHolder.clearContext();
            responseWriter.write(response, ex.errorCode(), ex.getMessage());
        }
    }
}
