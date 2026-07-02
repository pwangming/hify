package com.hify.knowledge.controller;

import com.hify.common.page.PageResult;
import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.knowledge.dto.DatasetResponse;
import com.hify.knowledge.service.DatasetService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DatasetController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class DatasetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private DatasetService datasetService;

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private DatasetResponse sample() {
        return new DatasetResponse(10L, "客服知识库", "售后答疑", 7L,
                OffsetDateTime.parse("2026-07-02T10:00:00+08:00"),
                OffsetDateTime.parse("2026-07-02T10:00:00+08:00"));
    }

    @Test
    void 列表_成员可访问_返回PageResult且Long为string() throws Exception {
        when(datasetService.page(any(), eq(1), eq(20)))
                .thenReturn(PageResult.of(List.of(sample()), 1, 1, 20));
        mockMvc.perform(get("/api/v1/knowledge/datasets?page=1&size=20")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list[0].id").value("10"))   // Long→string
                .andExpect(jsonPath("$.data.list[0].ownerId").value("7"))
                .andExpect(jsonPath("$.data.total").value("1"));
    }

    @Test
    void 未登录_401() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/datasets")).andExpect(status().isUnauthorized());
    }

    @Test
    void 详情_GET带id() throws Exception {
        when(datasetService.get(10L)).thenReturn(sample());
        mockMvc.perform(get("/api/v1/knowledge/datasets/10")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("客服知识库"));
    }

    @Test
    void 创建_名称为空_400并带字段错误() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/datasets")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void 创建_成功_返回完整资源() throws Exception {
        when(datasetService.create(any(), any())).thenReturn(sample());
        mockMvc.perform(post("/api/v1/knowledge/datasets")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"name\":\"客服知识库\",\"description\":\"售后答疑\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("10"))
                .andExpect(jsonPath("$.data.name").value("客服知识库"));
    }

    @Test
    void 更新_PUT带id() throws Exception {
        when(datasetService.update(eq(10L), any(), any())).thenReturn(sample());
        mockMvc.perform(put("/api/v1/knowledge/datasets/10")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"name\":\"新名\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void 删除_成功_data为null() throws Exception {
        mockMvc.perform(delete("/api/v1/knowledge/datasets/10")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());
        verify(datasetService).delete(eq(10L), any());
    }
}
