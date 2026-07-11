package com.hify.workflow.service.engine;

import com.hify.workflow.dto.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConditionNodeExecutorTest {

    private ConditionNodeExecutor executor;
    private RunContext ctx;

    @BeforeEach
    void setUp() {
        executor = new ConditionNodeExecutor();
        ctx = new RunContext(7L, 42L);
        ctx.putOutput("kb", Map.of("count", 2));
    }

    private GraphNode node(String left, String op, String right) {
        return new GraphNode("if_1", "condition", Map.of("left", left, "operator", op, "right", right));
    }

    @Test
    void 渲染左值_数值比较为真_inputs落渲染后的值() {
        NodeResult result = executor.execute(node("{{kb.count}}", ">", "0"), ctx);

        assertEquals(Boolean.TRUE, result.outputs().get("result"));
        assertEquals("2", result.inputs().get("left"));
        assertEquals(">", result.inputs().get("operator"));
        assertEquals("0", result.inputs().get("right"));
    }

    @Test
    void 比较为假_result为false() {
        NodeResult result = executor.execute(node("{{kb.count}}", "==", "0"), ctx);
        assertEquals(Boolean.FALSE, result.outputs().get("result"));
    }

    @Test
    void 数字比较遇非数字_抛NodeExecutionException_携带渲染后inputs() {
        ctx.putOutput("llm_1", Map.of("text", "查到了"));
        NodeExecutionException ex = assertThrows(NodeExecutionException.class,
                () -> executor.execute(node("{{llm_1.text}}", ">", "0"), ctx));
        assertEquals("查到了", ex.inputs().get("left"));
        assertEquals(IllegalArgumentException.class, ex.getCause().getClass());
    }
}
