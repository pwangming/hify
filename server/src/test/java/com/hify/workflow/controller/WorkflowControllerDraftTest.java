package com.hify.workflow.controller;

import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.workflow.dto.DraftResponse;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class WorkflowControllerDraftTest {

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

    private DraftResponse sample() {
        GraphDef graph = new GraphDef(
                List.of(new GraphNode("start", "start", Map.of()),
                        new GraphNode("end", "end", Map.of())),
                List.of(new GraphEdge("start", "end")));
        return new DraftResponse(graph, OffsetDateTime.parse("2026-07-09T10:00:00+08:00"));
    }

    @Test
    void 读草稿_返回graph结构() throws Exception {
        when(draftService.getDraft(42L)).thenReturn(sample());
        mockMvc.perform(get("/api/v1/workflow/apps/42/draft")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.graph.nodes[0].id").value("start"))
                .andExpect(jsonPath("$.data.graph.edges[0].target").value("end"));
    }

    @Test
    void 读草稿_无草稿data为null仍200() throws Exception {
        when(draftService.getDraft(42L)).thenReturn(null);
        mockMvc.perform(get("/api/v1/workflow/apps/42/draft")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 保存草稿_全量PUT() throws Exception {
        when(draftService.saveDraft(eq(42L), any(GraphDef.class), any())).thenReturn(sample());
        mockMvc.perform(put("/api/v1/workflow/apps/42/draft")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"graph": {"nodes": [{"id":"start","type":"start","data":{}},
                                                      {"id":"end","type":"end","data":{}}],
                                           "edges": [{"source":"start","target":"end"}]}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void 保存草稿_缺graph报10001() throws Exception {
        mockMvc.perform(put("/api/v1/workflow/apps/42/draft")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void 未登录_401() throws Exception {
        mockMvc.perform(get("/api/v1/workflow/apps/42/draft"))
                .andExpect(status().isUnauthorized());
    }
}
