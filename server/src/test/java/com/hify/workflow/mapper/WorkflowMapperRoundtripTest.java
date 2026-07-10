package com.hify.workflow.mapper;

import com.hify.support.PgIntegrationTest;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.entity.WorkflowDef;
import com.hify.workflow.entity.WorkflowNodeRun;
import com.hify.workflow.entity.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 三实体经 MP + TypeHandler 的真库读写往返：graph/inputs/outputs jsonb 不失真；分区表插入能回填自增 id。 */
class WorkflowMapperRoundtripTest extends PgIntegrationTest {

    @Autowired
    private WorkflowDefMapper defMapper;
    @Autowired
    private WorkflowRunMapper runMapper;
    @Autowired
    private WorkflowNodeRunMapper nodeRunMapper;

    @Test
    void def的graph往返不失真() {
        GraphDef graph = new GraphDef(
                List.of(new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "query", "required", true)))),
                        new GraphNode("llm_1", "llm", Map.of("modelId", "3", "userPrompt", "{{start.query}}")),
                        new GraphNode("end", "end", Map.of("outputs", List.of(Map.of("name", "answer", "value", "{{llm_1.text}}"))))),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
        WorkflowDef def = new WorkflowDef();
        def.setAppId(1L);
        def.setVersion(1);
        def.setGraph(graph);
        defMapper.insert(def);
        assertNotNull(def.getId());

        GraphDef loaded = defMapper.selectById(def.getId()).getGraph();
        assertEquals(3, loaded.nodes().size());
        assertEquals("llm", loaded.nodes().get(1).type());
        assertEquals("{{start.query}}", loaded.nodes().get(1).data().get("userPrompt"));
        assertEquals("end", loaded.edges().get(1).target());
    }

    @Test
    void run的inputs_outputs往返不失真() {
        WorkflowRun run = new WorkflowRun();
        run.setAppId(1L);
        run.setDefId(1L);
        run.setUserId(7L);
        run.setStatus("running");
        run.setInputs(Map.of("query", "我要退货"));
        runMapper.insert(run);
        assertNotNull(run.getId());

        WorkflowRun loaded = runMapper.selectById(run.getId());
        assertEquals("我要退货", loaded.getInputs().get("query"));
        assertEquals("running", loaded.getStatus());
    }

    @Test
    void node_run分区表插入回填id_updateById可收尾() {
        WorkflowNodeRun nr = new WorkflowNodeRun();
        nr.setRunId(1L);
        nr.setNodeId("llm_1");
        nr.setNodeType("llm");
        nr.setStatus("running");
        nodeRunMapper.insert(nr);
        assertNotNull(nr.getId());

        WorkflowNodeRun patch = new WorkflowNodeRun();
        patch.setId(nr.getId());
        patch.setStatus("succeeded");
        patch.setOutputs(Map.of("text", "退款类"));
        patch.setElapsedMs(1200L);
        nodeRunMapper.updateById(patch);

        WorkflowNodeRun loaded = nodeRunMapper.selectById(nr.getId());
        assertEquals("succeeded", loaded.getStatus());
        assertEquals("退款类", loaded.getOutputs().get("text"));
    }
}
