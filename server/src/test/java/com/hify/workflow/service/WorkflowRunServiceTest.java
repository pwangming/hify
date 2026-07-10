package com.hify.workflow.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.WorkflowAppView;
import com.hify.common.exception.BizException;
import com.hify.common.page.CursorResult;
import com.hify.infra.security.CurrentUser;
import com.hify.usage.api.UsageFacade;
import com.hify.workflow.config.WorkflowProperties;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.dto.RunResponse;
import com.hify.workflow.dto.RunSummaryView;
import com.hify.workflow.entity.WorkflowDef;
import com.hify.workflow.entity.WorkflowRun;
import com.hify.workflow.mapper.WorkflowDefMapper;
import com.hify.workflow.mapper.WorkflowRunMapper;
import com.hify.workflow.service.engine.EngineResult;
import com.hify.workflow.service.engine.GraphValidator;
import com.hify.workflow.service.engine.WorkflowEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRunServiceTest {

    private AppFacade appFacade;
    private UsageFacade usageFacade;
    private WorkflowDefMapper defMapper;
    private WorkflowRunMapper runMapper;
    private WorkflowEngine engine;
    private WorkflowRunStore store;
    private WorkflowRunService service;

    private final CurrentUser user = new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER);

    @BeforeEach
    void setUp() {
        appFacade = mock(AppFacade.class);
        usageFacade = mock(UsageFacade.class);
        defMapper = mock(WorkflowDefMapper.class);
        runMapper = mock(WorkflowRunMapper.class);
        engine = mock(WorkflowEngine.class);
        store = mock(WorkflowRunStore.class);
        WorkflowDraftService draftService =
                new WorkflowDraftService(defMapper, appFacade, new GraphValidator(new WorkflowProperties()));
        service = new WorkflowRunService(appFacade, usageFacade, draftService,
                new GraphValidator(new WorkflowProperties()), engine, store, runMapper);

        when(appFacade.findWorkflowApp(42L))
                .thenReturn(Optional.of(new WorkflowAppView(42L, 7L, true)));
        WorkflowDef def = new WorkflowDef();
        def.setId(5L);
        def.setAppId(42L);
        def.setGraph(legalGraph());
        when(defMapper.selectOne(any())).thenReturn(def);
    }

    private GraphDef legalGraph() {
        return new GraphDef(List.of(
                new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "query", "required", true)))),
                new GraphNode("llm_1", "llm", Map.of("modelId", "3", "userPrompt", "{{start.query}}")),
                new GraphNode("end", "end", Map.of("outputs", List.of(Map.of("name", "answer", "value", "{{llm_1.text}}"))))),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
    }

    private WorkflowRun runningRun(Long id) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setStatus("running");
        return run;
    }

    @Test
    void 应用已停用_报10006且不创建run() {
        when(appFacade.findWorkflowApp(42L))
                .thenReturn(Optional.of(new WorkflowAppView(42L, 7L, false)));
        BizException ex = assertThrows(BizException.class,
                () -> service.run(42L, Map.of("query", "hi"), user));
        assertEquals(10006, ex.errorCode().code());
        verify(store, never()).createRun(any(), any(), any(), any());
    }

    @Test
    void 缺必填输入_报10001且不创建run() {
        BizException ex = assertThrows(BizException.class, () -> service.run(42L, Map.of(), user));
        assertEquals(10001, ex.errorCode().code());
        assertTrue(ex.getMessage().contains("query"));
        verify(store, never()).createRun(any(), any(), any(), any());
    }

    @Test
    void 成功链路_配额先查_引擎执行_终态succeeded() {
        when(store.createRun(eq(42L), eq(5L), eq(7L), anyMap())).thenReturn(runningRun(100L));
        when(engine.execute(eq(100L), anyList(), anyMap(), any()))
                .thenReturn(EngineResult.success(Map.of("answer", "退款类")));
        WorkflowRun done = runningRun(100L);
        done.setStatus("succeeded");
        done.setOutputs(Map.of("answer", "退款类"));
        when(store.getRun(100L)).thenReturn(done);
        when(store.listNodeRuns(100L)).thenReturn(List.of());

        RunResponse resp = service.run(42L, Map.of("query", "我要退货"), user);

        verify(usageFacade).checkQuota(7L, 42L);
        verify(store).markRunSucceeded(eq(100L), eq(Map.of("answer", "退款类")), anyLong());
        assertEquals("succeeded", resp.status());
        assertEquals("退款类", resp.outputs().get("answer"));
    }

    @Test
    void 引擎失败_终态failed_正常返回不抛异常() {
        when(store.createRun(eq(42L), eq(5L), eq(7L), anyMap())).thenReturn(runningRun(100L));
        when(engine.execute(eq(100L), anyList(), anyMap(), any()))
                .thenReturn(EngineResult.failure("llm_1", "节点 llm_1 失败：模型不可用"));
        WorkflowRun failed = runningRun(100L);
        failed.setStatus("failed");
        failed.setErrorMessage("节点 llm_1 失败：模型不可用");
        when(store.getRun(100L)).thenReturn(failed);
        when(store.listNodeRuns(100L)).thenReturn(List.of());

        RunResponse resp = service.run(42L, Map.of("query", "hi"), user);

        verify(store).markRunFailed(eq(100L), eq("节点 llm_1 失败：模型不可用"), anyLong());
        assertEquals("failed", resp.status());
        assertEquals("节点 llm_1 失败：模型不可用", resp.errorMessage());
    }

    @Test
    void 引擎抛非预期异常_run兜底置failed_异常上抛() {
        when(store.createRun(eq(42L), eq(5L), eq(7L), anyMap())).thenReturn(runningRun(100L));
        when(engine.execute(eq(100L), anyList(), anyMap(), any()))
                .thenThrow(new RuntimeException("落库连接中断"));

        assertThrows(RuntimeException.class, () -> service.run(42L, Map.of("query", "hi"), user));

        // 不兜这条 run 会永久卡 running（僵尸自愈只在重启时跑）
        verify(store).markRunFailed(eq(100L), eq("系统异常，执行中断"), anyLong());
    }

    @Test
    void getRun不存在_报10005() {
        when(store.getRun(999L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.getRun(999L));
        assertEquals(10005, ex.errorCode().code());
    }

    @Test
    void listRuns_多一条探测hasMore并给nextCursor() {
        WorkflowRun r1 = runningRun(3L);
        r1.setCreateTime(OffsetDateTime.parse("2026-07-09T10:00:03+08:00"));
        WorkflowRun r2 = runningRun(2L);
        r2.setCreateTime(OffsetDateTime.parse("2026-07-09T10:00:02+08:00"));
        WorkflowRun r3 = runningRun(1L);
        r3.setCreateTime(OffsetDateTime.parse("2026-07-09T10:00:01+08:00"));
        when(runMapper.firstPage(42L, 3)).thenReturn(List.of(r1, r2, r3));   // limit=2 → 查 3 条

        CursorResult<RunSummaryView> page = service.listRuns(42L, null, 2);

        assertEquals(2, page.list().size());
        assertTrue(page.hasMore());
        RunCursor.Cursor next = RunCursor.decode(page.nextCursor());
        assertEquals(2L, next.id());   // 游标=页内最后一行
    }

    @Test
    void listRuns_不足一页hasMore为false游标null() {
        WorkflowRun only = runningRun(1L);
        only.setCreateTime(OffsetDateTime.parse("2026-07-09T10:00:01+08:00"));
        when(runMapper.firstPage(42L, 21)).thenReturn(List.of(only));

        CursorResult<RunSummaryView> page = service.listRuns(42L, null, 20);

        assertEquals(1, page.list().size());
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
    }

    @Test
    void listRuns_limit越界报10001() {
        BizException ex = assertThrows(BizException.class, () -> service.listRuns(42L, null, 101));
        assertEquals(10001, ex.errorCode().code());
    }
}
