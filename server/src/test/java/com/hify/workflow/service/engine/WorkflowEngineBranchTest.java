package com.hify.workflow.service.engine;

import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.service.WorkflowRunStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowEngineBranchTest {

    private WorkflowRunStore store;
    private WorkflowEngine engine;
    private final List<String> executedLlm = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = mock(WorkflowRunStore.class);
        when(store.createNodeRun(anyLong(), anyString(), anyString())).thenReturn(1L);
        NodeExecutor fakeLlm = new NodeExecutor() {
            @Override public String type() { return "llm"; }
            @Override public NodeResult execute(GraphNode node, RunContext ctx) {
                executedLlm.add(node.id());
                return new NodeResult(Map.of(), Map.of("text", "答案-" + node.id()));
            }
        };
        engine = new WorkflowEngine(List.of(new ConditionNodeExecutor(), fakeLlm), store);
    }

    /** start → if_1 →(true) llm_a →(直连) llm_a2 → end；if_1 →(false) llm_b → end。 */
    private List<GraphNode> nodes(String left, String op, String right) {
        return List.of(
                new GraphNode("start", "start", Map.of()),
                new GraphNode("if_1", "condition", Map.of("left", left, "operator", op, "right", right)),
                new GraphNode("llm_a", "llm", Map.of()),
                new GraphNode("llm_a2", "llm", Map.of()),
                new GraphNode("llm_b", "llm", Map.of()),
                new GraphNode("end", "end", Map.of("outputs", List.of(
                        Map.of("name", "r", "value", "{{llm_a2.text}}{{llm_b.text}}")))));
    }

    private List<GraphEdge> edges() {
        return List.of(new GraphEdge("start", "if_1"),
                new GraphEdge("if_1", "llm_a", "true"),
                new GraphEdge("llm_a", "llm_a2"),
                new GraphEdge("if_1", "llm_b", "false"),
                new GraphEdge("llm_a2", "end"), new GraphEdge("llm_b", "end"));
    }

    @Test
    void 条件为真_走true路_false路skipped_汇合end渲染跳过侧为空() {
        EngineResult result = engine.execute(9L, nodes("1", "==", "1"), edges(),
                Map.of(), new RunContext(7L, 42L));

        assertTrue(result.succeeded());
        assertEquals(List.of("llm_a", "llm_a2"), executedLlm);
        assertEquals("答案-llm_a2", result.outputs().get("r"));   // {{llm_b.text}} 渲染为空串
        verify(store).createSkippedNodeRun(9L, "llm_b", "llm");
    }

    @Test
    void 条件为假_走false路_true路连锁skipped() {
        EngineResult result = engine.execute(9L, nodes("1", "==", "2"), edges(),
                Map.of(), new RunContext(7L, 42L));

        assertTrue(result.succeeded());
        assertEquals(List.of("llm_b"), executedLlm);
        assertEquals("答案-llm_b", result.outputs().get("r"));
        verify(store).createSkippedNodeRun(9L, "llm_a", "llm");
        verify(store).createSkippedNodeRun(9L, "llm_a2", "llm");   // 连锁跳过
    }

    @Test
    void 直线图无分支_全执行_零skipped回归() {
        List<GraphNode> line = List.of(
                new GraphNode("start", "start", Map.of()),
                new GraphNode("llm_1", "llm", Map.of()),
                new GraphNode("end", "end", Map.of("outputs", List.of(
                        Map.of("name", "r", "value", "{{llm_1.text}}")))));
        List<GraphEdge> lineEdges = List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end"));

        EngineResult result = engine.execute(9L, line, lineEdges, Map.of(), new RunContext(7L, 42L));

        assertTrue(result.succeeded());
        assertEquals("答案-llm_1", result.outputs().get("r"));
        verify(store, never()).createSkippedNodeRun(anyLong(), anyString(), anyString());
    }

    @Test
    void 执行过的节点缺字段_仍报错不吞() {
        List<GraphNode> line = List.of(
                new GraphNode("start", "start", Map.of()),
                new GraphNode("llm_1", "llm", Map.of()),
                new GraphNode("end", "end", Map.of("outputs", List.of(
                        Map.of("name", "r", "value", "{{llm_1.noSuchField}}")))));
        List<GraphEdge> lineEdges = List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end"));

        EngineResult result = engine.execute(9L, line, lineEdges, Map.of(), new RunContext(7L, 42L));

        assertTrue(!result.succeeded());
        assertTrue(result.errorMessage().contains("end"));
    }
}
