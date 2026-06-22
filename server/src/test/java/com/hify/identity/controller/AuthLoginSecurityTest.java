package com.hify.identity.controller;

import com.hify.identity.dto.LoginResponse;
import com.hify.identity.service.AuthService;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证登录路径 {@code /api/v1/identity/login} 在安全链上是 permitAll——不带令牌也能进到控制器。
 * 用 @WebMvcTest 装 Web+Security 切片并 @Import infra 安全栈（参照 SecurityConfigTest）；AuthService 被 mock。
 * 这一半证明"无需令牌即可登录入口可达"；"令牌→受保护路由放行"的另一半由 SecurityConfigTest 覆盖。
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AuthLoginSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    void 登录入口_无令牌_放行可达控制器() throws Exception {
        when(authService.login(any(), any()))
                .thenReturn(new LoginResponse("tok-123", 7L, "alice", "member"));

        mockMvc.perform(post("/api/v1/identity/login")
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"pw123456\"}"))
                .andExpect(status().isOk())               // 不是 401：说明 permitAll 生效
                .andExpect(jsonPath("$.data.token").value("tok-123"));
    }
}
