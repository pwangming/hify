package com.hify.identity.controller;

import com.hify.identity.constant.IdentityError;
import com.hify.identity.dto.LoginResponse;
import com.hify.identity.service.AuthService;
import com.hify.common.exception.BizException;
import com.hify.infra.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthController 逻辑测试：standaloneSetup 轻量挂载控制器 + 全局异常处理器（不启容器、不连库、不过安全链）。
 * 验证成功信封、@Valid 校验失败(10001)、业务异常(11001)→HTTP 状态映射。AuthService 被 mock。
 */
class AuthControllerTest {

    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 登录成功_返回200与token信封() throws Exception {
        when(authService.login(eq("alice"), eq("pw123456")))
                .thenReturn(new LoginResponse("tok-123", 7L, "alice", "member"));

        mockMvc.perform(post("/api/v1/identity/login")
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"pw123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("tok-123"))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.role").value("member"));
    }

    @Test
    void 用户名为空_400且10001() throws Exception {
        mockMvc.perform(post("/api/v1/identity/login")
                        .contentType("application/json")
                        .content("{\"username\":\"\",\"password\":\"pw123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void 登录失败_业务异常11001映射为401() throws Exception {
        when(authService.login(eq("alice"), eq("wrong")))
                .thenThrow(new BizException(IdentityError.BAD_CREDENTIALS));

        mockMvc.perform(post("/api/v1/identity/login")
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(11001))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }
}
