package com.hify.workflow.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.WorkflowAppView;
import com.hify.common.exception.BizException;
import com.hify.infra.security.CurrentUser;
import com.hify.workflow.config.WorkflowProperties;
import com.hify.workflow.dto.DraftResponse;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.entity.WorkflowDef;
import com.hify.workflow.mapper.WorkflowDefMapper;
import com.hify.workflow.service.engine.GraphValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowDraftServiceTest {

    private WorkflowDefMapper defMapper;
    private AppFacade appFacade;
    private WorkflowDraftService service;

    private final CurrentUser owner = new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER);
    private final CurrentUser other = new CurrentUser(8L, "eve", CurrentUser.ROLE_MEMBER);
    private final CurrentUser admin = new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN);

    @BeforeEach
    void setUp() {
        defMapper = mock(WorkflowDefMapper.class);
        appFacade = mock(AppFacade.class);
        WorkflowProperties props = new WorkflowProperties();
        service = new WorkflowDraftService(defMapper, appFacade, new GraphValidator(props));
        when(appFacade.findWorkflowApp(42L))
                .thenReturn(Optional.of(new WorkflowAppView(42L, 7L, true)));
    }

    private GraphDef legalGraph() {
        return new GraphDef(List.of(
                new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "query", "required", true)))),
                new GraphNode("llm_1", "llm", Map.of("modelId", "3", "userPrompt", "{{start.query}}")),
                new GraphNode("end", "end", Map.of("outputs", List.of(Map.of("name", "answer", "value", "{{llm_1.text}}"))))),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
    }

    @Test
    void app不存在或非workflow_报10005() {
        when(appFacade.findWorkflowApp(99L)).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class, () -> service.getDraft(99L));
        assertEquals(10005, ex.errorCode().code());
    }

    @Test
    void 非owner非admin保存_报10004() {
        BizException ex = assertThrows(BizException.class,
                () -> service.saveDraft(42L, legalGraph(), other));
        assertEquals(10004, ex.errorCode().code());
        verify(defMapper, never()).upsertDraft(any(), any());
    }

    @Test
    void 非法图_报18001且不落库() {
        GraphDef illegal = new GraphDef(List.of(
                new GraphNode("start", "start", Map.of())), List.of());
        BizException ex = assertThrows(BizException.class,
                () -> service.saveDraft(42L, illegal, owner));
        assertEquals(18001, ex.errorCode().code());
        verify(defMapper, never()).upsertDraft(any(), any());
    }

    @Test
    void owner保存合法图_upsert并返回回读结果() {
        WorkflowDef stored = new WorkflowDef();
        stored.setAppId(42L);
        stored.setVersion(1);
        stored.setGraph(legalGraph());
        when(defMapper.selectOne(any())).thenReturn(stored);

        DraftResponse resp = service.saveDraft(42L, legalGraph(), owner);

        verify(defMapper).upsertDraft(eq(42L), any(GraphDef.class));
        assertEquals(3, resp.graph().nodes().size());
    }

    @Test
    void admin可保存他人应用的草稿() {
        when(defMapper.selectOne(any())).thenReturn(new WorkflowDef());
        service.saveDraft(42L, legalGraph(), admin);
        verify(defMapper).upsertDraft(eq(42L), any(GraphDef.class));
    }

    @Test
    void getDraft无草稿返回null_有草稿返回graph() {
        when(defMapper.selectOne(any())).thenReturn(null);
        assertNull(service.getDraft(42L));

        WorkflowDef stored = new WorkflowDef();
        stored.setGraph(legalGraph());
        when(defMapper.selectOne(any())).thenReturn(stored);
        assertEquals(3, service.getDraft(42L).graph().nodes().size());
    }

    @Test
    void requireDraft无草稿_报10005() {
        when(defMapper.selectOne(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.requireDraft(42L));
        assertEquals(10005, ex.errorCode().code());
    }
}
