package com.hify.infra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.Result;
import com.hify.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 在 Servlet 过滤器/安全处理器里手写统一 {@link Result} 信封。
 *
 * <p>为什么需要它：{@link com.hify.infra.web.GlobalExceptionHandler} 只能接住 <b>Controller 内</b>
 * 抛出的异常；而认证拦截发生在过滤器链（{@link JwtAuthenticationFilter}）和安全入口
 * （EntryPoint / AccessDeniedHandler）里，跑在 Spring MVC 之外，异常处理器够不着。
 * 这些地方的 401/403 必须自己把 JSON 写进响应，本类保证它们与全局信封格式一致。
 *
 * <p>traceId 仍会带上：{@link com.hify.infra.web.TraceIdFilter} 在最外层（最高优先级）先于安全
 * 过滤器执行，此刻 MDC 里已有 traceId，{@code Result.fail} 会自动取到。
 */
@Component
public class SecurityResponseWriter {

    private final ObjectMapper objectMapper;

    public SecurityResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ErrorCode error) throws IOException {
        write(response, error, error.defaultMessage());
    }

    public void write(HttpServletResponse response, ErrorCode error, String message) throws IOException {
        response.setStatus(error.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.fail(error, message));
    }
}
