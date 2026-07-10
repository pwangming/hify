package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.workflow.config.WorkflowProperties;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphValidatorTest {

    private GraphValidator validator;

    @BeforeEach
    void setUp() {
        WorkflowProperties props = new WorkflowProperties();
        props.setMaxNodes(50);
        validator = new GraphValidator(props);
    }

    private GraphNode start() {
        return new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "query", "required", true))));
    }

    private GraphNode llm(String id, String userPrompt) {
        return new GraphNode(id, "llm", Map.of("modelId", "3", "userPrompt", userPrompt));
    }

    private GraphNode end(String value) {
        return new GraphNode("end", "end", Map.of("outputs", List.of(Map.of("name", "answer", "value", value))));
    }

    private GraphDef legal() {
        return new GraphDef(List.of(start(), llm("llm_1", "{{start.query}}"), end("{{llm_1.text}}")),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
    }

    private String failMessage(GraphDef graph) {
        BizException ex = assertThrows(BizException.class, () -> validator.validateAndOrder(graph));
        assertEquals(18001, ex.errorCode().code());
        return ex.getMessage();
    }

    @Test
    void 合法线性图_返回拓扑序() {
        List<GraphNode> ordered = validator.validateAndOrder(legal());
        assertEquals(List.of("start", "llm_1", "end"), ordered.stream().map(GraphNode::id).toList());
    }

    @Test
    void 空图_报18001() {
        assertTrue(failMessage(new GraphDef(List.of(), List.of())).contains("节点"));
        assertTrue(failMessage(null).contains("节点"));
    }

    @Test
    void 超过节点数上限_报18001() {
        List<GraphNode> nodes = new ArrayList<>(List.of(start()));
        List<GraphEdge> edges = new ArrayList<>();
        String prev = "start";
        for (int i = 1; i <= 50; i++) {          // start + 50 个 llm + end = 52 > 50
            nodes.add(llm("llm_" + i, "hi"));
            edges.add(new GraphEdge(prev, "llm_" + i));
            prev = "llm_" + i;
        }
        nodes.add(end("{{llm_50.text}}"));
        edges.add(new GraphEdge(prev, "end"));
        assertTrue(failMessage(new GraphDef(nodes, edges)).contains("上限"));
    }

    @Test
    void 节点id重复_报18001() {
        GraphDef g = new GraphDef(List.of(start(), llm("dup", "hi"), llm("dup", "hi"), end("x")),
                List.of(new GraphEdge("start", "dup"), new GraphEdge("dup", "end")));
        assertTrue(failMessage(g).contains("重复"));
    }

    @Test
    void 缺start或多个end_报18001() {
        GraphDef noStart = new GraphDef(List.of(llm("llm_1", "hi"), end("x")),
                List.of(new GraphEdge("llm_1", "end")));
        assertTrue(failMessage(noStart).contains("start"));
        GraphDef twoEnd = new GraphDef(
                List.of(start(), new GraphNode("end", "end", Map.of()), new GraphNode("end2", "end", Map.of())),
                List.of(new GraphEdge("start", "end"), new GraphEdge("start", "end2")));
        assertTrue(failMessage(twoEnd).contains("end"));
    }

    @Test
    void 未知节点类型_报18001() {
        GraphDef g = new GraphDef(List.of(start(), new GraphNode("x", "magic", Map.of()), end("v")),
                List.of(new GraphEdge("start", "x"), new GraphEdge("x", "end")));
        assertTrue(failMessage(g).contains("magic"));
    }

    @Test
    void llm节点缺modelId或userPrompt_报18001() {
        GraphNode noModel = new GraphNode("llm_1", "llm", Map.of("userPrompt", "hi"));
        GraphDef g1 = new GraphDef(List.of(start(), noModel, end("x")),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
        assertTrue(failMessage(g1).contains("modelId"));
        GraphNode noPrompt = new GraphNode("llm_1", "llm", Map.of("modelId", "3"));
        GraphDef g2 = new GraphDef(List.of(start(), noPrompt, end("x")),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
        assertTrue(failMessage(g2).contains("userPrompt"));
    }

    @Test
    void 边引用不存在的节点_报18001() {
        GraphDef g = new GraphDef(List.of(start(), end("x")),
                List.of(new GraphEdge("start", "ghost"), new GraphEdge("ghost", "end")));
        assertTrue(failMessage(g).contains("ghost"));
    }

    @Test
    void 游离节点_报18001() {
        GraphDef g = new GraphDef(List.of(start(), llm("llm_1", "hi"), llm("island", "hi"), end("x")),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end"),
                        new GraphEdge("start", "island")));   // island 到不了 end
        assertTrue(failMessage(g).contains("island"));
    }

    @Test
    void 有环_报18001() {
        GraphDef g = new GraphDef(List.of(start(), llm("a", "hi"), llm("b", "hi"), end("x")),
                List.of(new GraphEdge("start", "a"), new GraphEdge("a", "b"), new GraphEdge("b", "a"),
                        new GraphEdge("b", "end")));
        assertTrue(failMessage(g).contains("环"));
    }

    @Test
    void 变量引用下游节点_报18001() {
        // llm_1 引用了排在自己后面的 end 的输出
        GraphDef g = new GraphDef(List.of(start(), llm("llm_1", "{{end.answer}}"), end("x")),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
        assertTrue(failMessage(g).contains("end"));
    }

    @Test
    void 变量引用不存在的节点_报18001() {
        GraphDef g = new GraphDef(List.of(start(), llm("llm_1", "{{ghost.text}}"), end("x")),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
        assertTrue(failMessage(g).contains("ghost"));
    }
}
