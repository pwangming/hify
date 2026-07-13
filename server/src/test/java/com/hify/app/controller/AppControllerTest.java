package com.hify.app.controller;

import com.hify.app.api.dto.AppConfig;
import com.hify.app.dto.AppResponse;
import com.hify.app.service.AppService;
import com.hify.common.page.PageResult;
import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AppController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AppControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AppService appService;

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private AppResponse sample() {
        return new AppResponse(10L, "客服助手", "答疑", "chat", 5L, "GPT-4o", true, new AppConfig("你是客服", false),
                List.of(),
                7L, "enabled", OffsetDateTime.parse("2026-06-24T10:00:00+08:00"),
                OffsetDateTime.parse("2026-06-24T10:00:00+08:00"));
    }

    @Test
    void 列表_成员可访问_返回PageResult且Long为string() throws Exception {
        when(appService.page(any(), any(), eq(1), eq(20)))
                .thenReturn(PageResult.of(List.of(sample()), 1, 1, 20));
        mockMvc.perform(get("/api/v1/app/apps?page=1&size=20")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list[0].id").value("10"))   // Long→string
                .andExpect(jsonPath("$.data.total").value("1"));        // long→string
    }

    @Test
    void 未登录_401() throws Exception {
        mockMvc.perform(get("/api/v1/app/apps")).andExpect(status().isUnauthorized());
    }

    @Test
    void 创建_名称为空_400并带字段错误() throws Exception {
        mockMvc.perform(post("/api/v1/app/apps")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"name\":\"\",\"type\":\"chat\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void 创建_成功_返回完整资源() throws Exception {
        when(appService.create(any(), any())).thenReturn(sample());
        mockMvc.perform(post("/api/v1/app/apps")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"name\":\"客服助手\",\"type\":\"chat\",\"config\":{\"systemPrompt\":\"你是客服\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("客服助手"))
                .andExpect(jsonPath("$.data.config.systemPrompt").value("你是客服"));
    }
}
