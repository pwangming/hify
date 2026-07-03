package com.hify.knowledge.controller;

import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.knowledge.service.ReembedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminKnowledgeController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AdminKnowledgeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ReembedService reembedService;

    @Test
    void 全量重嵌_admin_200() throws Exception {
        String token = jwtService.generateToken(new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN));
        mockMvc.perform(post("/api/v1/admin/knowledge/documents/reembed")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        verify(reembedService).start();
    }

    @Test
    void 全量重嵌_member_403() throws Exception {
        String token = jwtService.generateToken(new CurrentUser(2L, "bob", CurrentUser.ROLE_MEMBER));
        mockMvc.perform(post("/api/v1/admin/knowledge/documents/reembed")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
