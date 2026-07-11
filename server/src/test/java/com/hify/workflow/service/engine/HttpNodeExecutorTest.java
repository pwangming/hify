package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.infra.outbound.OutboundResponse;
import com.hify.workflow.dto.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpNodeExecutorTest {

    private OutboundHttpClient http;
    private HttpNodeExecutor executor;
    private RunContext ctx;

    @BeforeEach
    void setUp() {
        http = mock(OutboundHttpClient.class);
        executor = new HttpNodeExecutor(http);
        ctx = new RunContext(7L, 42L);
        ctx.putOutput("start", Map.of("uid", "u1", "token", "secret-token"));
    }

    private GraphNode node(Map<String, Object> data) {
        return new GraphNode("http_1", "http", data);
    }

    @Test
    void 渲染url与headers_输出status_body_headers() {
        when(http.send(eq("GET"), eq("https://api.example.com/u/u1"),
                eq(Map.of("Authorization", "Bearer secret-token")), isNull()))
                .thenReturn(new OutboundResponse(200, "{\"name\":\"张三\"}", Map.of("content-type", "application/json")));

        NodeResult result = executor.execute(node(Map.of(
                "method", "get",
                "url", "https://api.example.com/u/{{start.uid}}",
                "headers", Map.of("Authorization", "Bearer {{start.token}}"))), ctx);

        assertEquals(200, result.outputs().get("status"));
        assertEquals("{\"name\":\"张三\"}", result.outputs().get("body"));
        assertEquals("application/json",
                ((Map<?, ?>) result.outputs().get("headers")).get("content-type"));
        assertEquals("https://api.example.com/u/u1", result.inputs().get("url"));
    }

    @Test
    void 敏感头落inputs前脱敏_发出去的不脱敏() {
        when(http.send(anyString(), anyString(), anyMap(), any()))
                .thenReturn(new OutboundResponse(200, "", Map.of()));

        NodeResult result = executor.execute(node(Map.of(
                "method", "GET", "url", "https://a.com",
                "headers", Map.of("Authorization", "Bearer secret-token", "X-Trace", "t1"))), ctx);

        Map<?, ?> loggedHeaders = (Map<?, ?>) result.inputs().get("headers");
        assertEquals("***", loggedHeaders.get("Authorization"));   // 脱敏
        assertEquals("t1", loggedHeaders.get("X-Trace"));          // 普通头保留
    }

    @Test
    void 四百四_节点成功_status如实() {
        when(http.send(anyString(), anyString(), anyMap(), any()))
                .thenReturn(new OutboundResponse(404, "not found", Map.of()));

        NodeResult result = executor.execute(node(Map.of("method", "GET", "url", "https://a.com/x")), ctx);
        assertEquals(404, result.outputs().get("status"));
    }

    @Test
    void GET忽略body_传null给客户端() {
        when(http.send(eq("GET"), anyString(), anyMap(), isNull()))
                .thenReturn(new OutboundResponse(200, "", Map.of()));

        NodeResult result = executor.execute(node(Map.of(
                "method", "GET", "url", "https://a.com", "body", "{\"ignored\":1}")), ctx);
        assertNull(result.inputs().get("body"));   // inputs 里也是 null（如实反映实际发送）
    }

    @Test
    void 客户端抛Biz_转NodeExecutionException_inputs已脱敏() {
        when(http.send(anyString(), anyString(), anyMap(), any()))
                .thenThrow(new BizException(CommonError.PARAM_INVALID, "目标地址禁止访问（内网/保留地址）：localhost"));

        NodeExecutionException ex = assertThrows(NodeExecutionException.class,
                () -> executor.execute(node(Map.of(
                        "method", "GET", "url", "http://localhost:8080/x",
                        "headers", Map.of("Cookie", "sid=abc"))), ctx));

        assertEquals("***", ((Map<?, ?>) ex.inputs().get("headers")).get("Cookie"));
        assertEquals(BizException.class, ex.getCause().getClass());
    }
}
