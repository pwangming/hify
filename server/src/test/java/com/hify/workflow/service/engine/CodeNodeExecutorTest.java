package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.infra.outbound.SandboxClient;
import com.hify.infra.outbound.SandboxResult;
import com.hify.workflow.dto.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeNodeExecutorTest {

    private final SandboxClient sandbox = mock(SandboxClient.class);
    private final CodeNodeExecutor executor = new CodeNodeExecutor(sandbox);

    private GraphNode node(String code, Map<String, Object> inputs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("inputs", inputs);
        return new GraphNode("code_1", "code", data);
    }

    @Test
    void type为code() {
        assertEquals("code", executor.type());
    }

    @Test
    void 渲染上游变量后调沙箱_输出进结果() {
        RunContext ctx = new RunContext(1L, 2L);
        ctx.putOutput("start", Map.of("question", "hello world"));
        when(sandbox.run(eq("def main(text): return {'n': 1}"), any()))
                .thenReturn(new SandboxResult(true, Map.of("n", 2), null));

        NodeResult r = executor.execute(
                node("def main(text): return {'n': 1}", Map.of("text", "{{start.question}}")), ctx);

        assertEquals("hello world", r.inputs().get("text"));  // 渲染后的实参落 inputs 快照
        assertEquals(2, r.outputs().get("n"));
    }

    @Test
    void 沙箱返回失败_抛NodeExecutionException且cause为Biz18002() {
        RunContext ctx = new RunContext(1L, 2L);
        when(sandbox.run(any(), any()))
                .thenReturn(new SandboxResult(false, null, "执行出错：NameError: x"));

        NodeExecutionException ex = assertThrows(NodeExecutionException.class,
                () -> executor.execute(node("def main(): return {}", Map.of()), ctx));
        assertTrue(ex.getCause() instanceof BizException);
        assertEquals(18002, ((BizException) ex.getCause()).errorCode().code());
        assertTrue(ex.getCause().getMessage().contains("NameError"));
    }

    @Test
    void 沙箱调用抛异常_包成NodeExecutionException带inputs快照() {
        RunContext ctx = new RunContext(1L, 2L);
        ctx.putOutput("start", Map.of("q", "hi"));
        when(sandbox.run(any(), any()))
                .thenThrow(new BizException(com.hify.common.exception.CommonError.DEPENDENCY_UNAVAILABLE, "沙箱繁忙"));

        NodeExecutionException ex = assertThrows(NodeExecutionException.class,
                () -> executor.execute(node("def main(q): return {}", Map.of("q", "{{start.q}}")), ctx));
        assertEquals("hi", ex.inputs().get("q"));
    }
}
