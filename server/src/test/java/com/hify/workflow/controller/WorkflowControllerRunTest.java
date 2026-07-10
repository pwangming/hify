package com.hify.workflow.controller;

import com.hify.common.page.CursorResult;
import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.workflow.dto.NodeRunView;
import com.hify.workflow.dto.RunResponse;
import com.hify.workflow.dto.RunSummaryView;
import com.hify.workflow.service.WorkflowDraftService;
import com.hify.workflow.service.WorkflowRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class WorkflowControllerRunTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @MockitoBean
    private WorkflowDraftService draftService;
    @MockitoBean
    private WorkflowRunService runService;

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private RunResponse succeededRun() {
        return new RunResponse(100L, "succeeded", Map.of("query", "我要退货"), Map.of("answer", "退款类"),
                null, 1200L, OffsetDateTime.parse("2026-07-09T10:00:00+08:00"),
                List.of(new NodeRunView(1L, "start", "start", "succeeded", Map.of(), Map.of(),
                        null, 1L, OffsetDateTime.parse("2026-07-09T10:00:00+08:00"))));
    }

    @Test
    void 触发运行_返回完整run且Long为string() throws Exception {
        when(runService.run(eq(42L), any(), any())).thenReturn(succeededRun());
        mockMvc.perform(post("/api/v1/workflow/apps/42/runs")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputs\": {\"query\": \"我要退货\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("100"))
                .andExpect(jsonPath("$.data.status").value("succeeded"))
                .andExpect(jsonPath("$.data.outputs.answer").value("退款类"))
                .andExpect(jsonPath("$.data.nodeRuns[0].nodeId").value("start"));
    }

    @Test
    void 触发运行_body可省略() throws Exception {
        when(runService.run(eq(42L), isNull(), any())).thenReturn(succeededRun());
        mockMvc.perform(post("/api/v1/workflow/apps/42/runs")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void 运行历史_游标分页结构() throws Exception {
        when(runService.listRuns(42L, null, 20)).thenReturn(CursorResult.of(
                List.of(new RunSummaryView(100L, "succeeded", null, 1200L,
                        OffsetDateTime.parse("2026-07-09T10:00:00+08:00"))),
                "abc", true));
        mockMvc.perform(get("/api/v1/workflow/apps/42/runs")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].id").value("100"))
                .andExpect(jsonPath("$.data.nextCursor").value("abc"))
                .andExpect(jsonPath("$.data.hasMore").value(true));
    }

    @Test
    void 运行详情() throws Exception {
        when(runService.getRun(100L)).thenReturn(succeededRun());
        mockMvc.perform(get("/api/v1/workflow/runs/100")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("succeeded"));
    }
}
