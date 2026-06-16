package com.hify.infra.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link TraceIdFilter} 的单元测试，用 Spring 提供的 Mock 对象模拟一次请求。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 无traceId头时生成并写回响应头() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertNotNull(response.getHeader(TraceIdFilter.TRACE_ID_HEADER), "应写回 X-Trace-Id 头");
    }

    @Test
    void 复用请求头里已有的traceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "given-trace");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals("given-trace", response.getHeader(TraceIdFilter.TRACE_ID_HEADER));
    }

    @Test
    void 处理链中MDC可见_结束后清理() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "given-trace");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 用一个会读取 MDC 的链，验证「过滤期间」traceId 确实在上下文里
        AtomicReference<String> seenInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenInChain.set(MDC.get(TraceIdFilter.TRACE_ID_KEY));

        filter.doFilter(request, response, chain);

        assertEquals("given-trace", seenInChain.get(), "过滤链执行时 MDC 应有 traceId");
        assertNull(MDC.get(TraceIdFilter.TRACE_ID_KEY), "请求结束后 MDC 必须被清理，避免线程复用串味");
    }
}
