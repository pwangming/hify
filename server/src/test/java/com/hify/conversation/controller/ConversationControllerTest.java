package com.hify.conversation.controller;

import com.hify.conversation.dto.MessageView;
import com.hify.conversation.dto.SendMessageResponse;
import com.hify.conversation.service.ConversationService;
import com.hify.conversation.service.StreamEvent;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConversationController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class ConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ConversationService conversationService;

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(42L, "alice", CurrentUser.ROLE_MEMBER));
    }

    private MessageView assistant() {
        return new MessageView(200L, "assistant", "你好，我是助手", 12, 8,
                OffsetDateTime.parse("2026-06-26T10:00:00+08:00"));
    }

    @Test
    void 发消息_成员可访问_Long为string() throws Exception {
        when(conversationService.send(eq(7L), eq(null), eq("你好"), any()))
                .thenReturn(new SendMessageResponse(100L, assistant()));
        mockMvc.perform(post("/api/v1/conversation/messages")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"appId\":\"7\",\"content\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.conversationId").value("100"))   // Long→string
                .andExpect(jsonPath("$.data.message.id").value("200"))
                .andExpect(jsonPath("$.data.message.role").value("assistant"))
                .andExpect(jsonPath("$.data.message.promptTokens").value("12")); // 数字也 string
    }

    @Test
    void 发消息_content为空_400带字段错误() throws Exception {
        mockMvc.perform(post("/api/v1/conversation/messages")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"appId\":\"7\",\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void 拉历史_返回数组() throws Exception {
        when(conversationService.history(eq(100L), any())).thenReturn(List.of(assistant()));
        mockMvc.perform(get("/api/v1/conversation/messages?conversationId=100")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("200"));
    }

    @Test
    void 未登录_401() throws Exception {
        mockMvc.perform(get("/api/v1/conversation/messages?conversationId=100"))
                .andExpect(status().isUnauthorized());
    }

    private com.hify.conversation.dto.ConversationView conv() {
        return new com.hify.conversation.dto.ConversationView(100L, "你好",
                OffsetDateTime.parse("2026-06-29T10:00:00+08:00"));
    }

    @Test
    void 会话列表_返回数组_id为string() throws Exception {
        when(conversationService.listConversations(eq(7L), any())).thenReturn(List.of(conv()));
        mockMvc.perform(get("/api/v1/conversation/conversations?appId=7")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("100"))   // Long→string
                .andExpect(jsonPath("$.data[0].title").value("你好"));
    }

    @Test
    void 流式发消息_输出message与done事件() throws Exception {
        when(conversationService.sendStream(eq(7L), eq(null), eq("你好"), any()))
                .thenReturn(reactor.core.publisher.Flux.just(
                        new StreamEvent.Delta("你好，"),
                        new StreamEvent.Done(100L, 200L, 12, 8)));

        MvcResult res = mockMvc.perform(post("/api/v1/conversation/messages/stream")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"appId\":\"7\",\"content\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(res))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body)
                .contains("event:message").contains("\"delta\":\"你好，\"")
                .contains("event:done").contains("\"conversationId\":\"100\"")
                .contains("\"messageId\":\"200\"").contains("\"promptTokens\":\"12\"");
    }

    @Test
    void 流式发消息_content为空_400JSON_不开流() throws Exception {
        mockMvc.perform(post("/api/v1/conversation/messages/stream")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"appId\":\"7\",\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }
}
