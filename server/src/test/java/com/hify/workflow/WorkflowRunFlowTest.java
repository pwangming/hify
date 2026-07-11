package com.hify.workflow;

import com.hify.infra.security.CurrentUser;
import com.hify.provider.api.ProviderFacade;
import com.hify.support.PgIntegrationTest;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.dto.RunResponse;
import com.hify.workflow.service.WorkflowDraftService;
import com.hify.workflow.service.WorkflowRunService;
import com.hify.workflow.service.engine.LlmCallResult;
import com.hify.workflow.service.engine.LlmCaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W1 黄金链路（真库真服务，只 mock LLM 边界）：建 app → 存草稿 → 触发 → 断言 run/node_run 落库。
 * TokenUsedEvent→usage 计量是 AFTER_COMMIT 监听，测试事务回滚不触发，不在此断言（手动验收覆盖）。
 */
class WorkflowRunFlowTest extends PgIntegrationTest {

    @Autowired
    private WorkflowDraftService draftService;
    @Autowired
    private WorkflowRunService runService;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ProviderFacade providerFacade;
    @MockitoBean
    private LlmCaller llmCaller;
    @MockitoBean
    private com.hify.knowledge.api.KnowledgeFacade knowledgeFacade;

    private Long appId;
    private final CurrentUser owner = new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER);

    @BeforeEach
    void seed() {
        appId = jdbc.queryForObject(
                "insert into app (name, type, owner_id) values ('W1工单分类器', 'workflow', 7) returning id",
                Long.class);
        when(providerFacade.getChatClient(anyLong())).thenReturn(mock(ChatClient.class));
    }

    private GraphDef graph() {
        return new GraphDef(List.of(
                new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "query", "required", true)))),
                new GraphNode("llm_1", "llm", Map.of("modelId", "3", "systemPrompt", "你是客服",
                        "userPrompt", "分类：{{start.query}}")),
                new GraphNode("end", "end", Map.of("outputs", List.of(Map.of("name", "answer", "value", "{{llm_1.text}}"))))),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
    }

    @Test
    void 黄金链路_存草稿_触发_三节点日志齐全() {
        when(llmCaller.call(any(), eq("你是客服"), eq("分类：我要退货")))
                .thenReturn(new LlmCallResult("退款类", 10, 5));

        draftService.saveDraft(appId, graph(), owner);
        RunResponse resp = runService.run(appId, Map.of("query", "我要退货"), owner);

        assertEquals("succeeded", resp.status());
        assertEquals("退款类", resp.outputs().get("answer"));
        assertNotNull(resp.id());
        assertEquals(3, resp.nodeRuns().size());
        assertEquals(List.of("start", "llm_1", "end"),
                resp.nodeRuns().stream().map(n -> n.nodeId()).toList());
        assertTrue(resp.nodeRuns().stream().allMatch(n -> "succeeded".equals(n.status())));
        // 变量替换后的实际输入已落 node_run（spec §1.3）
        assertEquals("分类：我要退货", resp.nodeRuns().get(1).inputs().get("userPrompt"));

        // 真库行数据兜底断言（不经 DTO）
        Integer nodeRows = jdbc.queryForObject(
                "select count(*) from workflow_node_run where run_id = ?", Integer.class, resp.id());
        assertEquals(3, nodeRows);
        String runStatus = jdbc.queryForObject(
                "select status from workflow_run where id = ?", String.class, resp.id());
        assertEquals("succeeded", runStatus);
    }

    @Test
    void 节点失败_run置failed_end不执行_HTTP语义由status表达() {
        when(llmCaller.call(any(), any(), any()))
                .thenThrow(new IllegalStateException("连接被重置"));

        draftService.saveDraft(appId, graph(), owner);
        RunResponse resp = runService.run(appId, Map.of("query", "我要退货"), owner);   // 不抛异常

        assertEquals("failed", resp.status());
        assertTrue(resp.errorMessage().contains("llm_1"));
        assertEquals(2, resp.nodeRuns().size());   // start + llm_1，end 未开工
        assertEquals("failed", resp.nodeRuns().get(1).status());
    }

    @Test
    void 运行历史_游标翻页穿真库() {
        when(llmCaller.call(any(), any(), any())).thenReturn(new LlmCallResult("ok", 1, 1));
        draftService.saveDraft(appId, graph(), owner);
        for (int i = 0; i < 3; i++) {
            runService.run(appId, Map.of("query", "q" + i), owner);
        }

        var page1 = runService.listRuns(appId, null, 2);
        assertEquals(2, page1.list().size());
        assertTrue(page1.hasMore());

        var page2 = runService.listRuns(appId, page1.nextCursor(), 2);
        assertEquals(1, page2.list().size());
        assertTrue(!page2.hasMore());
    }

    /** W2：start → kb → llm → end 的 RAG 链路图。 */
    private GraphDef ragGraph() {
        return new GraphDef(List.of(
                new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "query", "required", true)))),
                new GraphNode("kb", "knowledge-retrieval", Map.of("datasetIds", List.of(1), "query", "{{start.query}}")),
                new GraphNode("llm_1", "llm", Map.of("modelId", "3",
                        "userPrompt", "参考资料：{{kb.text}}\n请回答：{{start.query}}")),
                new GraphNode("end", "end", Map.of("outputs", List.of(Map.of("name", "answer", "value", "{{llm_1.text}}"))))),
                List.of(new GraphEdge("start", "kb"), new GraphEdge("kb", "llm_1"), new GraphEdge("llm_1", "end")));
    }

    @Test
    void RAG链路_检索结果注入下游提示词_四节点日志齐全() {
        when(knowledgeFacade.retrieve(eq(List.of(1L)), eq("怎么退货")))
                .thenReturn(List.of(new com.hify.knowledge.api.RetrievedChunk(
                        11L, 1L, "客服手册", "七天无理由退货", 0.9)));
        when(llmCaller.call(any(), eq(null), eq("参考资料：[1] 七天无理由退货\n请回答：怎么退货")))
                .thenReturn(new LlmCallResult("支持七天无理由退货", 20, 8));

        draftService.saveDraft(appId, ragGraph(), owner);
        RunResponse resp = runService.run(appId, Map.of("query", "怎么退货"), owner);

        assertEquals("succeeded", resp.status());
        assertEquals("支持七天无理由退货", resp.outputs().get("answer"));
        assertEquals(List.of("start", "kb", "llm_1", "end"),
                resp.nodeRuns().stream().map(n -> n.nodeId()).toList());
        // kb 节点 inputs/outputs 落库如实
        assertEquals("怎么退货", resp.nodeRuns().get(1).inputs().get("query"));
        assertEquals(1, resp.nodeRuns().get(1).outputs().get("count"));
        Integer nodeRows = jdbc.queryForObject(
                "select count(*) from workflow_node_run where run_id = ?", Integer.class, resp.id());
        assertEquals(4, nodeRows);
    }

    @Test
    void 知识库被删_kb节点失败_run置failed_下游不执行() {
        org.mockito.Mockito.doThrow(new com.hify.common.exception.BizException(
                        com.hify.common.exception.CommonError.NOT_FOUND, "知识库不存在或已删除"))
                .when(knowledgeFacade).validateDatasetIds(any());

        draftService.saveDraft(appId, ragGraph(), owner);
        RunResponse resp = runService.run(appId, Map.of("query", "怎么退货"), owner);   // 不抛异常

        assertEquals("failed", resp.status());
        assertTrue(resp.errorMessage().contains("kb"));
        assertEquals(2, resp.nodeRuns().size());   // start + kb，llm/end 未开工
        assertEquals("failed", resp.nodeRuns().get(1).status());
        assertEquals("怎么退货", resp.nodeRuns().get(1).inputs().get("query"));   // 渲染后输入落库供排障
    }

    /** W3a：start → kb → if(count>0) → llm_hit / llm_miss → end 的分支图。 */
    private GraphDef branchGraph() {
        return new GraphDef(List.of(
                new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "query", "required", true)))),
                new GraphNode("kb", "knowledge-retrieval", Map.of("datasetIds", List.of(1), "query", "{{start.query}}")),
                new GraphNode("if_1", "condition", Map.of("left", "{{kb.count}}", "operator", ">", "right", "0")),
                new GraphNode("llm_hit", "llm", Map.of("modelId", "3", "userPrompt", "根据资料回答：{{kb.text}}")),
                new GraphNode("llm_miss", "llm", Map.of("modelId", "3", "userPrompt", "礼貌告知没查到：{{start.query}}")),
                new GraphNode("end", "end", Map.of("outputs", List.of(
                        Map.of("name", "answer", "value", "{{llm_hit.text}}{{llm_miss.text}}"))))),
                List.of(new GraphEdge("start", "kb"), new GraphEdge("kb", "if_1"),
                        new GraphEdge("if_1", "llm_hit", "true"),
                        new GraphEdge("if_1", "llm_miss", "false"),
                        new GraphEdge("llm_hit", "end"), new GraphEdge("llm_miss", "end")));
    }

    @Test
    void 分支_kb命中_走精答路_兜底路skipped() {
        when(knowledgeFacade.retrieve(any(), any())).thenReturn(List.of(
                new com.hify.knowledge.api.RetrievedChunk(11L, 1L, "手册", "七天无理由退货", 0.9)));
        when(llmCaller.call(any(), any(), eq("根据资料回答：[1] 七天无理由退货")))
                .thenReturn(new LlmCallResult("支持七天退货", 10, 5));

        draftService.saveDraft(appId, branchGraph(), owner);
        RunResponse resp = runService.run(appId, Map.of("query", "怎么退货"), owner);

        assertEquals("succeeded", resp.status());
        assertEquals("支持七天退货", resp.outputs().get("answer"));   // 跳过侧渲染空串
        assertEquals(6, resp.nodeRuns().size());   // 全节点都有记录（含 skipped）
        var byId = resp.nodeRuns().stream()
                .collect(java.util.stream.Collectors.toMap(n -> n.nodeId(), n -> n.status()));
        assertEquals("succeeded", byId.get("llm_hit"));
        assertEquals("skipped", byId.get("llm_miss"));
        // 真库兜底：skipped 行确实过了 V22 check
        Integer skippedRows = jdbc.queryForObject(
                "select count(*) from workflow_node_run where run_id = ? and status = 'skipped'",
                Integer.class, resp.id());
        assertEquals(1, skippedRows);
    }

    @Test
    void 分支_kb未命中_走兜底路_精答路skipped() {
        when(knowledgeFacade.retrieve(any(), any())).thenReturn(List.of());
        when(llmCaller.call(any(), any(), eq("礼貌告知没查到：怎么退货")))
                .thenReturn(new LlmCallResult("抱歉没有找到相关资料", 8, 4));

        draftService.saveDraft(appId, branchGraph(), owner);
        RunResponse resp = runService.run(appId, Map.of("query", "怎么退货"), owner);

        assertEquals("succeeded", resp.status());
        assertEquals("抱歉没有找到相关资料", resp.outputs().get("answer"));
        var byId = resp.nodeRuns().stream()
                .collect(java.util.stream.Collectors.toMap(n -> n.nodeId(), n -> n.status()));
        assertEquals("skipped", byId.get("llm_hit"));
        assertEquals("succeeded", byId.get("llm_miss"));
        // 条件节点 inputs 落了实际比较值（排障可用性）
        var ifRun = resp.nodeRuns().stream().filter(n -> "if_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals("0", ifRun.inputs().get("left"));
    }
}
