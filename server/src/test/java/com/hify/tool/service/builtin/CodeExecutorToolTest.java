package com.hify.tool.service.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.outbound.SandboxClient;
import com.hify.infra.outbound.SandboxResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class CodeExecutorToolTest {

    private final SandboxClient sandbox = Mockito.mock(SandboxClient.class);
    private final CodeExecutorTool tool = new CodeExecutorTool(sandbox, new ObjectMapper());

    @Test
    void name_与_schema_齐备() {
        assertThat(tool.name()).isEqualTo("code_executor");
        assertThat(tool.inputSchema()).contains("\"code\"").contains("required");
    }

    @Test
    void 执行成功_返回outputs的json() {
        when(sandbox.run(eq("def main():\n    return {'answer': 4}"), any()))
                .thenReturn(new SandboxResult(true, Map.of("answer", 4), null));
        String out = tool.execute("{\"code\":\"def main():\\n    return {'answer': 4}\"}");
        assertThat(out).contains("answer").contains("4");
    }

    @Test
    void 沙箱业务失败_ok为false_返回错误文本_不抛() {
        when(sandbox.run(any(), any()))
                .thenReturn(new SandboxResult(false, Map.of(), "执行出错：ZeroDivisionError"));
        String out = tool.execute("{\"code\":\"def main():\\n    return {'x': 1/0}\"}");
        assertThat(out).contains("错误").contains("ZeroDivisionError");
    }

    @Test
    void 沙箱不可达_BizException_被吞成错误文本() {
        when(sandbox.run(any(), any()))
                .thenThrow(new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "沙箱调用失败：refused"));
        String out = tool.execute("{\"code\":\"def main():\\n    return {}\"}");
        assertThat(out).contains("错误").contains("refused");
    }

    @Test
    void 缺code_返回错误文本() {
        assertThat(tool.execute("{}")).contains("code");
    }
}
