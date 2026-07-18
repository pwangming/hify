package com.hify.provider.controller;

import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.provider.dto.ModelResponse;
import com.hify.provider.service.AiModelService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminModelController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AdminModelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AiModelService aiModelService;

    @MockitoBean
    private com.hify.provider.service.ModelConnectionService modelConnectionService;

    private String adminToken() {
        return jwtService.generateToken(new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN));
    }

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(2L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private ModelResponse sample() {
        return new ModelResponse(
                7L, 1L, "chat", "GPT-4o", "gpt-4o", "enabled", OffsetDateTime.now(), null, null);
    }

    @Test
    void 列某供应商模型_admin_200且id字符串() throws Exception {
        when(aiModelService.listByProvider(1L)).thenReturn(List.of(sample()));

        mockMvc.perform(get("/api/v1/admin/provider/providers/1/models")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("7"))
                .andExpect(jsonPath("$.data[0].providerId").value("1"))
                .andExpect(jsonPath("$.data[0].modelKey").value("gpt-4o"));
    }

    @Test
    void 创建模型_admin_200() throws Exception {
        when(aiModelService.create(eq(1L), any())).thenReturn(sample());

        mockMvc.perform(post("/api/v1/admin/provider/providers/1/models")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"type\":\"chat\",\"name\":\"GPT-4o\",\"modelKey\":\"gpt-4o\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("chat"));
    }

    @Test
    void 更新模型_admin_200() throws Exception {
        when(aiModelService.update(eq(7L), any())).thenReturn(sample());

        mockMvc.perform(put("/api/v1/admin/provider/models/7")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"name\":\"GPT-4o 改\",\"modelKey\":\"gpt-4o\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 删除模型_admin_200且data不存在() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/provider/models/7")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 启用模型_admin_200() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/models/7/enable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 禁用模型_admin_200() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/models/7/disable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 创建模型_member_403且10004() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/providers/1/models")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"type\":\"chat\",\"name\":\"x\",\"modelKey\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10004));
    }

    @Test
    void 创建模型_type非法_400且10001() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/providers/1/models")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"type\":\"image\",\"name\":\"x\",\"modelKey\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void 测试连通_admin_200返回sample() throws Exception {
        when(modelConnectionService.test(7L))
                .thenReturn(new com.hify.provider.api.dto.ModelTestResponse("pong"));

        mockMvc.perform(post("/api/v1/admin/provider/models/7/test")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sample").value("pong"));
    }

    @Test
    void 测试连通_member_403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/models/7/test")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10004));
    }

    @Test
    void 测试连通_供应商不可用_503且12003() throws Exception {
        when(modelConnectionService.test(7L))
                .thenThrow(new com.hify.common.exception.BizException(
                        com.hify.provider.constant.ProviderError.PROVIDER_UNAVAILABLE));

        mockMvc.perform(post("/api/v1/admin/provider/models/7/test")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(12003));
    }
}
