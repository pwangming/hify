package com.hify.infra.security;

import com.hify.app.controller.HealthController;
import com.hify.common.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 整条安检链（{@link SecurityConfig} + JWT 过滤器 + 入口/拒绝处理器）的集成测试。
 *
 * <p>用 {@code @WebMvcTest} 只装 Web + Security 切片（<b>不连数据库、不跑 Flyway</b>），
 * 用两个仅供测试的探针接口验证放行/拦截/角色四类路由规则。
 */
@WebMvcTest(controllers = HealthController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityConfigTest.ProbeController.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    private String bearer(String role) {
        return "Bearer " + jwtService.generateToken(new CurrentUser(1L, "u", role));
    }

    @Test
    void 健康检查_放行_无需令牌() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void 受保护路由_无令牌_401且业务码10002() throws Exception {
        mockMvc.perform(get("/api/v1/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void 受保护路由_持有效令牌_放行() throws Exception {
        mockMvc.perform(get("/api/v1/probe").header("Authorization", bearer(CurrentUser.ROLE_MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("probe-ok"));
    }

    @Test
    void 无效令牌_401且业务码10002() throws Exception {
        mockMvc.perform(get("/api/v1/probe").header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void admin路由_member令牌_403且业务码10004() throws Exception {
        mockMvc.perform(get("/api/v1/admin/probe").header("Authorization", bearer(CurrentUser.ROLE_MEMBER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10004));
    }

    @Test
    void admin路由_admin令牌_放行() throws Exception {
        mockMvc.perform(get("/api/v1/admin/probe").header("Authorization", bearer(CurrentUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("admin-probe-ok"));
    }

    /** 仅供本测试用的探针接口：一个普通受保护路由，一个 admin 路由。 */
    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/probe")
        Result<String> probe() {
            return Result.ok("probe-ok");
        }

        @GetMapping("/api/v1/admin/probe")
        Result<String> adminProbe() {
            return Result.ok("admin-probe-ok");
        }
    }
}
