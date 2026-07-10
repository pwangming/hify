package com.hify.workflow.service;

import com.hify.support.PgIntegrationTest;
import com.hify.workflow.entity.WorkflowNodeRun;
import com.hify.workflow.entity.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowRunStoreTest extends PgIntegrationTest {

    @Autowired
    private WorkflowRunStore store;
    @Autowired
    private WorkflowPartitionMaintainer maintainer;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createRun落库running并回填id() {
        WorkflowRun run = store.createRun(42L, 1L, 7L, Map.of("query", "hi"));
        assertNotNull(run.getId());
        WorkflowRun loaded = store.getRun(run.getId());
        assertEquals("running", loaded.getStatus());
        assertEquals("hi", loaded.getInputs().get("query"));
    }

    @Test
    void 节点日志_开工到收尾() {
        WorkflowRun run = store.createRun(42L, 1L, 7L, Map.of());
        Long nodeRunId = store.createNodeRun(run.getId(), "llm_1", "llm");
        assertNotNull(nodeRunId);
        store.finishNodeRun(nodeRunId, true, Map.of("userPrompt", "hi"), Map.of("text", "ok"), null, 120L);

        List<WorkflowNodeRun> nodeRuns = store.listNodeRuns(run.getId());
        assertEquals(1, nodeRuns.size());
        assertEquals("succeeded", nodeRuns.get(0).getStatus());
        assertEquals("ok", nodeRuns.get(0).getOutputs().get("text"));
        assertEquals(120L, nodeRuns.get(0).getElapsedMs());
    }

    @Test
    void run终态_成功带outputs_失败带原因() {
        WorkflowRun ok = store.createRun(42L, 1L, 7L, Map.of());
        store.markRunSucceeded(ok.getId(), Map.of("answer", "退款类"), 800L);
        WorkflowRun loadedOk = store.getRun(ok.getId());
        assertEquals("succeeded", loadedOk.getStatus());
        assertEquals("退款类", loadedOk.getOutputs().get("answer"));
        assertNull(loadedOk.getErrorMessage());

        WorkflowRun bad = store.createRun(42L, 1L, 7L, Map.of());
        store.markRunFailed(bad.getId(), "节点 llm_1 失败：模型不可用", 300L);
        WorkflowRun loadedBad = store.getRun(bad.getId());
        assertEquals("failed", loadedBad.getStatus());
        assertEquals("节点 llm_1 失败：模型不可用", loadedBad.getErrorMessage());
    }

    @Test
    void 僵尸重置_running的run与nodeRun全部置failed() {
        WorkflowRun zombie = store.createRun(42L, 1L, 7L, Map.of());
        store.createNodeRun(zombie.getId(), "llm_1", "llm");
        WorkflowRun done = store.createRun(42L, 1L, 7L, Map.of());
        store.markRunSucceeded(done.getId(), Map.of(), 10L);

        int reset = store.resetZombies();

        assertTrue(reset >= 2);   // 1 run + 1 node_run（≥ 防其他用例遗留）
        assertEquals("failed", store.getRun(zombie.getId()).getStatus());
        assertEquals("服务重启中断", store.getRun(zombie.getId()).getErrorMessage());
        assertEquals("failed", store.listNodeRuns(zombie.getId()).get(0).getStatus());
        assertEquals("succeeded", store.getRun(done.getId()).getStatus());   // 终态不受影响
    }

    @Test
    void 分区维护_确保未来月份分区存在() {
        maintainer.onStartup();
        YearMonth next = YearMonth.now().plusMonths(3);
        String name = String.format("workflow_node_run_%d_%02d", next.getYear(), next.getMonthValue());
        Integer count = jdbc.queryForObject(
                "select count(*) from pg_class where relname = ?", Integer.class, name);
        assertEquals(1, count);
    }
}
