package com.hify.infra.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求注入 traceId（api-standards.md 第 3 节）。
 *
 * <p>作用链路：
 * <ol>
 *   <li>从请求头 {@code X-Trace-Id} 取（便于跨服务/网关串联）；没有就生成一个短随机串；</li>
 *   <li>放进日志 MDC，键名 {@code traceId}——之后所有日志行、{@code Result.ok()}/异常处理器
 *       组装的响应都会带上它；</li>
 *   <li>写回响应头 {@code X-Trace-Id}，用户报障时报这个值，我们就能 grep 到全链路日志；</li>
 *   <li><b>finally 里务必 remove</b>：线程会被复用（尤其虚拟线程载体），不清会串味
 *       （coding-standards.md 第 20 条）。</li>
 * </ol>
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} 让它尽量早执行，保证后续环节都能拿到 traceId。
 * 继承 {@link OncePerRequestFilter} 确保一次请求只过一遍（转发/包含不会重复执行）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TRACE_ID_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
