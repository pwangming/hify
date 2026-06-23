package com.hify.identity.controller;

import com.hify.identity.dto.UserView;
import com.hify.identity.service.AdminUserService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUserController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AdminUserService adminUserService;

    private String adminToken() {
        return jwtService.generateToken(new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN));
    }

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(2L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private UserView sample() {
        return new UserView(7L, "alice", CurrentUser.ROLE_MEMBER, "enabled", OffsetDateTime.now());
    }

    @Test
    void 创建用户_admin_200且id为字符串且无密码哈希() throws Exception {
        when(adminUserService.create(eq("alice"), any(), eq("member"))).thenReturn(sample());

        mockMvc.perform(post("/api/v1/admin/identity/users")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"rawpw1234\",\"role\":\"member\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("7"))             // Long→字符串
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist()); // 不泄露哈希
    }

    @Test
    void 列表_admin_200() throws Exception {
        when(adminUserService.list()).thenReturn(List.of(sample()));

        mockMvc.perform(get("/api/v1/admin/identity/users")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value("alice"));
    }

    @Test
    void 停用_admin_200() throws Exception {
        when(adminUserService.disable(7L)).thenReturn(sample());

        mockMvc.perform(post("/api/v1/admin/identity/users/7/disable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 启用_admin_200() throws Exception {
        when(adminUserService.enable(7L)).thenReturn(sample());

        mockMvc.perform(post("/api/v1/admin/identity/users/7/enable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 重置密码_admin_200() throws Exception {
        mockMvc.perform(put("/api/v1/admin/identity/users/7/password")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"password\":\"newpw5678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 改角色_admin_200() throws Exception {
        when(adminUserService.changeRole(eq(7L), eq("admin"))).thenReturn(sample());

        mockMvc.perform(put("/api/v1/admin/identity/users/7/role")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"role\":\"admin\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 删除_admin_200() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/identity/users/7")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 创建用户_member_403且10004() throws Exception {
        mockMvc.perform(post("/api/v1/admin/identity/users")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"rawpw1234\",\"role\":\"member\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10004));
    }

    @Test
    void 列表_无令牌_401且10002() throws Exception {
        mockMvc.perform(get("/api/v1/admin/identity/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void 创建用户_校验失败_400且10001带字段数组() throws Exception {
        mockMvc.perform(post("/api/v1/admin/identity/users")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"username\":\"\",\"password\":\"short\",\"role\":\"boss\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data").isArray());
    }
}
