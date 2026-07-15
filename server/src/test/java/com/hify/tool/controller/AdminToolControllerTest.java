package com.hify.tool.controller;

import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.tool.dto.ToolAdminResponse;
import com.hify.tool.service.ToolAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminToolController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AdminToolControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ToolAdminService toolAdminService;

    private String adminToken() {
        return jwtService.generateToken(new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN));
    }

    private ToolAdminResponse sample() {
        return new ToolAdminResponse(9L, "petstore", "宠物", "openapi", true,
                1, 1L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void 列表_admin_200且返回工具列表() throws Exception {
        when(toolAdminService.list()).thenReturn(List.of(sample()));

        mockMvc.perform(get("/api/v1/admin/tool/tools")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value("9"))
                .andExpect(jsonPath("$.data[0].source").value("openapi"))
                .andExpect(jsonPath("$.data[0].operationCount").value(1));
    }

    @Test
    void 创建_admin_200且source为openapi() throws Exception {
        when(toolAdminService.create(any(), any())).thenReturn(sample());

        mockMvc.perform(post("/api/v1/admin/tool/tools")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"name\":\"petstore\",\"description\":\"宠物\",\"specText\":\"openapi: 3.0.0\\ninfo:\\n  title: Pet\\n  version: 1\\npaths: {}\",\"authHeaders\":[{\"name\":\"X-API-Key\",\"value\":\"k\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.source").value("openapi"));
    }

    @Test
    void 预览_admin_200且返回操作() throws Exception {
        when(toolAdminService.preview(any())).thenReturn(
                new com.hify.tool.dto.ToolPreviewResponse("https://api.example.com",
                        java.util.List.of(new com.hify.tool.dto.OperationView("getPet", "GET", "/pets/{id}", "查"))));

        mockMvc.perform(post("/api/v1/admin/tool/tools/preview")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"specText\":\"openapi: 3.0.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.baseUrl").value("https://api.example.com"))
                .andExpect(jsonPath("$.data.operations[0].opName").value("getPet"));
    }

    @Test
    void 列表_无令牌_401且10002() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tool/tools"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
    }
}
