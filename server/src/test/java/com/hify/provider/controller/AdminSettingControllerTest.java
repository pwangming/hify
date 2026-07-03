package com.hify.provider.controller;

import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.provider.dto.EmbeddingSettingResponse;
import com.hify.provider.service.EmbeddingSettingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminSettingController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AdminSettingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private EmbeddingSettingService embeddingSettingService;

    private String adminToken() {
        return jwtService.generateToken(new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN));
    }

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(2L, "bob", CurrentUser.ROLE_MEMBER));
    }

    @Test
    void 查设置_admin_200且Long为字符串() throws Exception {
        when(embeddingSettingService.get()).thenReturn(new EmbeddingSettingResponse(6L, "千问 v4"));
        mockMvc.perform(get("/api/v1/admin/provider/settings/embedding-model")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelId").value("6"))
                .andExpect(jsonPath("$.data.modelName").value("千问 v4"));
    }

    @Test
    void 查设置_未配置_modelId为null仍200() throws Exception {
        when(embeddingSettingService.get()).thenReturn(new EmbeddingSettingResponse(null, null));
        mockMvc.perform(get("/api/v1/admin/provider/settings/embedding-model")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelId").isEmpty());
    }

    @Test
    void 存设置_admin_200() throws Exception {
        when(embeddingSettingService.save(6L)).thenReturn(new EmbeddingSettingResponse(6L, "千问 v4"));
        mockMvc.perform(put("/api/v1/admin/provider/settings/embedding-model")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\": 6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelId").value("6"));
    }

    @Test
    void 存设置_缺modelId_400参数校验() throws Exception {
        mockMvc.perform(put("/api/v1/admin/provider/settings/embedding-model")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void member调admin接口_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/provider/settings/embedding-model")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isForbidden());
    }
}
