package com.hify.provider.controller;

import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.provider.api.dto.ModelView;
import com.hify.provider.service.ModelQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ModelQueryController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class ModelQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ModelQueryService modelQueryService;

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER));
    }

    @Test
    void 列表_成员可访问_id为string且providerName存在() throws Exception {
        when(modelQueryService.listUsableChatModels("chat"))
                .thenReturn(List.of(new ModelView(5L, "GPT-4o", "chat", "通义千问")));
        mockMvc.perform(get("/api/v1/provider/models")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value("5"))          // Long→string
                .andExpect(jsonPath("$.data[0].name").value("GPT-4o"))
                .andExpect(jsonPath("$.data[0].providerName").value("通义千问"));
        verify(modelQueryService).listUsableChatModels("chat");          // type 默认 chat 透传
    }

    @Test
    void 未登录_401() throws Exception {
        mockMvc.perform(get("/api/v1/provider/models")).andExpect(status().isUnauthorized());
    }
}
