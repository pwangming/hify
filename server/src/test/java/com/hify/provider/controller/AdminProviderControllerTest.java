package com.hify.provider.controller;

import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.provider.dto.ProviderResponse;
import com.hify.provider.service.ProviderService;
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

@WebMvcTest(AdminProviderController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AdminProviderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ProviderService providerService;

    private String adminToken() {
        return jwtService.generateToken(new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN));
    }

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(2L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private ProviderResponse sample() {
        return new ProviderResponse(7L, "通义-生产", "openai",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "enabled", "3456", OffsetDateTime.now(),
                null, null, null);
    }

    @Test
    void 列表_admin_200且id为字符串且无密文字段() throws Exception {
        when(providerService.list()).thenReturn(List.of(sample()));

        mockMvc.perform(get("/api/v1/admin/provider/providers")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value("7"))               // Long→字符串
                .andExpect(jsonPath("$.data[0].apiKeyTail").value("3456"))
                .andExpect(jsonPath("$.data[0].apiKeyCipher").doesNotExist()) // 不泄露密文
                .andExpect(jsonPath("$.data[0].apiKey").doesNotExist());
    }

    @Test
    void 创建_admin_200() throws Exception {
        when(providerService.create(any())).thenReturn(sample());

        mockMvc.perform(post("/api/v1/admin/provider/providers")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"name\":\"通义-生产\",\"protocol\":\"openai\","
                                + "\"baseUrl\":\"https://dashscope.aliyuncs.com/compatible-mode/v1\",\"apiKey\":\"sk-abcdef123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.protocol").value("openai"));
    }

    @Test
    void 更新_admin_200() throws Exception {
        when(providerService.update(eq(7L), any())).thenReturn(sample());

        mockMvc.perform(put("/api/v1/admin/provider/providers/7")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"name\":\"通义-生产\",\"protocol\":\"openai\","
                                + "\"baseUrl\":\"https://api.openai.com/v1\",\"apiKey\":\"\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 删除_admin_200且data不存在() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/provider/providers/7")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 启用_admin_200() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/providers/7/enable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 禁用_admin_200() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/providers/7/disable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 创建_member_403且10004() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/providers")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"protocol\":\"openai\",\"baseUrl\":\"https://a.com\",\"apiKey\":\"k\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10004));
    }

    @Test
    void 列表_无令牌_401且10002() throws Exception {
        mockMvc.perform(get("/api/v1/admin/provider/providers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void 创建_protocol非法_400且10001带字段数组() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/providers")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"protocol\":\"gemini\",\"baseUrl\":\"https://a.com\",\"apiKey\":\"k\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data").isArray());
    }
}
