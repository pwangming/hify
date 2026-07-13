package com.hify.tool.service.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.infra.outbound.OutboundResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class HttpRequestToolTest {

    private final OutboundHttpClient httpClient = Mockito.mock(OutboundHttpClient.class);
    private final HttpRequestTool tool = new HttpRequestTool(httpClient, new ObjectMapper());

    @Test
    void name_与_schema_齐备() {
        assertThat(tool.name()).isEqualTo("http_request");
        assertThat(tool.inputSchema()).contains("\"method\"").contains("\"url\"").contains("required");
    }

    @Test
    void 成功请求_返回状态码与响应体() {
        when(httpClient.send(eq("GET"), eq("https://api.example.com/x"), any(), any()))
                .thenReturn(new OutboundResponse(200, "{\"a\":1}", Map.of()));
        String out = tool.execute("{\"method\":\"GET\",\"url\":\"https://api.example.com/x\"}");
        assertThat(out).contains("200").contains("{\"a\":1}");
    }

    @Test
    void 方法不在白名单_返回错误文本_不抛() {
        String out = tool.execute("{\"method\":\"TRACE\",\"url\":\"https://x\"}");
        assertThat(out).contains("不支持").contains("TRACE");
    }

    @Test
    void 缺少url_返回错误文本() {
        String out = tool.execute("{\"method\":\"GET\"}");
        assertThat(out).contains("url");
    }

    @Test
    void 网络失败_BizException_被吞成错误文本() {
        when(httpClient.send(any(), any(), any(), any()))
                .thenThrow(new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "HTTP 请求失败：timeout"));
        String out = tool.execute("{\"method\":\"GET\",\"url\":\"https://x\"}");
        assertThat(out).contains("错误").contains("timeout");
    }

    @Test
    void 参数非json_返回错误文本() {
        String out = tool.execute("not-json");
        assertThat(out).contains("错误");
    }
}
