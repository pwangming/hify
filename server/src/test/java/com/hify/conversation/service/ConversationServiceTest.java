package com.hify.conversation.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.AppRuntimeView;
import com.hify.common.exception.BizException;
import com.hify.conversation.constant.ConversationError;
import com.hify.conversation.constant.MessageRole;
import com.hify.conversation.dto.ConversationView;
import com.hify.conversation.dto.SendMessageResponse;
import com.hify.conversation.entity.Conversation;
import com.hify.conversation.entity.Message;
import com.hify.infra.security.CurrentUser;
import com.hify.provider.api.ProviderFacade;
import com.hify.provider.constant.ProviderError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    private AppFacade appFacade;
    private ProviderFacade providerFacade;
    private ChatInvoker chatInvoker;
    private ConversationStore store;
    private QuotaGuard quotaGuard;
    private ConversationService service;

    private final CurrentUser member = new CurrentUser(42L, "alice", CurrentUser.ROLE_MEMBER);
    private final ChatClient chatClient = mock(ChatClient.class);

    @BeforeEach
    void setUp() {
        appFacade = mock(AppFacade.class);
        providerFacade = mock(ProviderFacade.class);
        chatInvoker = mock(ChatInvoker.class);
        store = mock(ConversationStore.class);
        quotaGuard = mock(QuotaGuard.class);
        service = new ConversationService(appFacade, providerFacade, chatInvoker, store, quotaGuard);
    }

    private void stubRunnableApp(String systemPrompt) {
        when(appFacade.findRunnableChatApp(eq(7L)))
                .thenReturn(Optional.of(new AppRuntimeView(7L, 5L, systemPrompt)));
        when(providerFacade.getChatClient(eq(5L))).thenReturn(chatClient);
    }

    private Message userMsg(String content) {
        Message m = new Message();
        m.setRole(MessageRole.USER.value());
        m.setContent(content);
        return m;
    }

    private Message assistantMsg(String content) {
        Message m = new Message();
        m.setRole(MessageRole.ASSISTANT.value());
        m.setContent(content);
        return m;
    }

    private Message savedAssistant() {
        Message m = new Message();
        m.setId(200L);
        m.setConversationId(100L);
        m.setRole(MessageRole.ASSISTANT.value());
        m.setContent("你好，我是助手");
        m.setPromptTokens(12);
        m.setCompletionTokens(8);
        return m;
    }

    @Test
    void send_新会话_三段时序_配额先行_返回assistant视图() {
        stubRunnableApp("你是客服");
        List<Message> window = List.of(userMsg("你好"));
        when(store.openTurn(eq(7L), eq(null), eq(42L), eq("你好")))
                .thenReturn(new TurnContext(100L, window, 300L, true));
        when(chatInvoker.invoke(eq(chatClient), eq("你是客服"), eq(window)))
                .thenReturn(new LlmReply("你好，我是助手", 12, 8));
        when(store.appendAssistant(eq(100L), eq("你好，我是助手"), eq(12), eq(8)))
                .thenReturn(savedAssistant());

        SendMessageResponse resp = service.send(7L, null, "你好", member);

        // 配额检查在落库前；窗口喂模型在两次 store 写之间
        InOrder order = inOrder(quotaGuard, store, chatInvoker);
        order.verify(quotaGuard).check(42L, 7L);
        order.verify(store).openTurn(7L, null, 42L, "你好");
        order.verify(chatInvoker).invoke(chatClient, "你是客服", window);
        order.verify(store).appendAssistant(100L, "你好，我是助手", 12, 8);

        assertEquals(100L, resp.conversationId());
        assertEquals(200L, resp.message().id());
        assertEquals("assistant", resp.message().role());
        assertEquals("你好，我是助手", resp.message().content());
        assertEquals(12, resp.message().promptTokens());
    }

    @Test
    void send_多轮_把store返回的窗口整体喂模型() {
        stubRunnableApp(null);
        List<Message> window = List.of(userMsg("第一句"), assistantMsg("回复一"), userMsg("第二句"));
        when(store.openTurn(any(), any(), any(), any())).thenReturn(new TurnContext(100L, window, 300L, false));
        when(chatInvoker.invoke(any(), any(), any())).thenReturn(new LlmReply("ok", 1, 1));
        when(store.appendAssistant(any(), any(), anyInt(), anyInt())).thenReturn(savedAssistant());

        service.send(7L, 100L, "第二句", member);

        // 窗口原样透传给 invoker（含历史，末位当前消息），systemPrompt 为 null 透传
        verify(chatInvoker).invoke(eq(chatClient), eq(null), eq(window));
    }

    @Test
    void send_应用不可对话_17001_且不落库不调模型() {
        when(appFacade.findRunnableChatApp(eq(7L))).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));
        assertEquals(ConversationError.APP_NOT_RUNNABLE, ex.errorCode());
        verify(store, never()).openTurn(any(), any(), any(), any());
        verify(chatInvoker, never()).invoke(any(), any(), any());
    }

    @Test
    void send_模型不可用_透传12002_user消息已落但不落assistant() {
        when(appFacade.findRunnableChatApp(eq(7L)))
                .thenReturn(Optional.of(new AppRuntimeView(7L, 5L, null)));
        when(store.openTurn(any(), any(), any(), any()))
                .thenReturn(new TurnContext(100L, List.of(userMsg("你好")), 300L, true));
        when(providerFacade.getChatClient(eq(5L)))
                .thenThrow(new BizException(ProviderError.MODEL_NOT_USABLE));

        BizException ex = assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));
        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
        verify(store).openTurn(7L, null, 42L, "你好"); // 事务A 已发生
        verify(store, never()).appendAssistant(any(), any(), anyInt(), anyInt());
    }

    @Test
    void send_模型调用故障_透传12003_不落assistant() {
        stubRunnableApp(null);
        when(store.openTurn(any(), any(), any(), any()))
                .thenReturn(new TurnContext(100L, List.of(userMsg("你好")), 300L, true));
        when(chatInvoker.invoke(any(), any(), any()))
                .thenThrow(new BizException(ProviderError.PROVIDER_UNAVAILABLE));

        BizException ex = assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));
        assertEquals(ProviderError.PROVIDER_UNAVAILABLE, ex.errorCode());
        verify(store, never()).appendAssistant(any(), any(), anyInt(), anyInt());
    }

    @Test
    void history_委托store_按当前用户过滤() {
        Message m = savedAssistant();
        when(store.listMessages(eq(100L), eq(42L))).thenReturn(List.of(m));

        var list = service.history(100L, member);

        assertEquals(1, list.size());
        assertEquals(200L, list.get(0).id());
        verify(store).listMessages(100L, 42L);
    }

    @Test
    void listConversations_委托store_映射为视图() {
        Conversation c = new Conversation();
        c.setId(1L);
        c.setTitle("会话一");
        c.setUpdateTime(OffsetDateTime.parse("2026-06-29T10:00:00+08:00"));
        when(store.listConversations(eq(7L), eq(42L))).thenReturn(List.of(c));

        List<ConversationView> views = service.listConversations(7L, member);

        assertEquals(1, views.size());
        assertEquals(1L, views.get(0).id());
        assertEquals("会话一", views.get(0).title());
        verify(store).listConversations(7L, 42L);
    }

    // ===== sendStream 辅助 =====

    private org.springframework.ai.chat.model.ChatResponse chunk(String text) {
        return new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
                new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage(text))));
    }

    private org.springframework.ai.chat.model.ChatResponse chunkWithUsage(String text, int p, int c) {
        return org.springframework.ai.chat.model.ChatResponse.builder()
                .generations(java.util.List.of(new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage(text))))
                .metadata(org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(p, c, p + c)).build())
                .build();
    }

    @Test
    void sendStream_增量吐字_结束落全文与usage_发Done() {
        stubRunnableApp("你是客服");
        when(store.openTurn(eq(7L), eq(null), eq(42L), eq("你好")))
                .thenReturn(new TurnContext(100L, java.util.List.of(userMsg("你好")), 300L, true));
        when(chatInvoker.invokeStream(eq(chatClient), eq("你是客服"), any()))
                .thenReturn(reactor.core.publisher.Flux.just(chunk("你好，"), chunkWithUsage("我是助手", 12, 8)));
        when(store.appendAssistant(eq(100L), eq("你好，我是助手"), eq(12), eq(8)))
                .thenReturn(savedAssistant());

        reactor.test.StepVerifier.create(service.sendStream(7L, null, "你好", member))
                .expectNext(new StreamEvent.Delta("你好，"))
                .expectNext(new StreamEvent.Delta("我是助手"))
                .expectNextMatches(e -> e instanceof StreamEvent.Done d
                        && d.conversationId() == 100L && d.messageId() == 200L
                        && d.promptTokens() == 12 && d.completionTokens() == 8)
                .verifyComplete();
        verify(store).appendAssistant(100L, "你好，我是助手", 12, 8);
    }

    @Test
    void sendStream_流报错_半截不落assistant() {
        stubRunnableApp(null);
        when(store.openTurn(any(), any(), any(), any()))
                .thenReturn(new TurnContext(100L, java.util.List.of(userMsg("你好")), 300L, true));
        when(chatInvoker.invokeStream(any(), any(), any()))
                .thenReturn(reactor.core.publisher.Flux.concat(
                        reactor.core.publisher.Flux.just(chunk("半")),
                        reactor.core.publisher.Flux.error(new BizException(ProviderError.PROVIDER_UNAVAILABLE))));

        reactor.test.StepVerifier.create(service.sendStream(7L, null, "你好", member))
                .expectNext(new StreamEvent.Delta("半"))
                .expectErrorMatches(e -> e instanceof BizException b
                        && b.errorCode() == ProviderError.PROVIDER_UNAVAILABLE)
                .verify();
        verify(store, never()).appendAssistant(any(), any(), anyInt(), anyInt());
    }

    @Test
    void sendStream_新会话_流报错_清理孤儿并抛错且不落assistant() {
        // 场景(a)：新建会话 + 流失败 → cleanupFailedTurn(cid, msgId, true) 被调、错误透传、appendAssistant 不调
        stubRunnableApp(null);
        when(store.openTurn(eq(7L), eq(null), eq(42L), eq("你好")))
                .thenReturn(new TurnContext(100L, java.util.List.of(userMsg("你好")), 300L, true));
        when(chatInvoker.invokeStream(any(), any(), any()))
                .thenReturn(reactor.core.publisher.Flux.error(
                        new BizException(ProviderError.PROVIDER_UNAVAILABLE)));

        reactor.test.StepVerifier.create(service.sendStream(7L, null, "你好", member))
                .expectErrorMatches(e -> e instanceof BizException b
                        && b.errorCode() == ProviderError.PROVIDER_UNAVAILABLE)
                .verify();
        verify(store).cleanupFailedTurn(100L, 300L, true);
        verify(store, never()).appendAssistant(any(), any(), anyInt(), anyInt());
    }

    @Test
    void sendStream_续聊_流报错_只删消息不删会话() {
        // 场景(b)：续聊（已有会话）+ 流失败 → cleanupFailedTurn(cid, msgId, false) 被调
        stubRunnableApp(null);
        when(store.openTurn(eq(7L), eq(100L), eq(42L), eq("继续")))
                .thenReturn(new TurnContext(100L, java.util.List.of(userMsg("继续")), 300L, false));
        when(chatInvoker.invokeStream(any(), any(), any()))
                .thenReturn(reactor.core.publisher.Flux.error(
                        new BizException(ProviderError.PROVIDER_UNAVAILABLE)));

        reactor.test.StepVerifier.create(service.sendStream(7L, 100L, "继续", member))
                .expectError()
                .verify();
        verify(store).cleanupFailedTurn(100L, 300L, false);
    }

    @Test
    void sendStream_成功完成_不清理孤儿() {
        // 场景(c)：正常完成 → cleanupFailedTurn 不调
        stubRunnableApp(null);
        when(store.openTurn(any(), any(), any(), any()))
                .thenReturn(new TurnContext(100L, java.util.List.of(userMsg("你好")), 300L, true));
        when(chatInvoker.invokeStream(any(), any(), any()))
                .thenReturn(reactor.core.publisher.Flux.just(chunkWithUsage("答案", 5, 3)));
        when(store.appendAssistant(any(), any(), anyInt(), anyInt())).thenReturn(savedAssistant());

        reactor.test.StepVerifier.create(service.sendStream(7L, null, "你好", member))
                .expectNext(new StreamEvent.Delta("答案"))
                .expectNextMatches(e -> e instanceof StreamEvent.Done)
                .verifyComplete();
        verify(store, never()).cleanupFailedTurn(any(), any(), anyBoolean());
    }
}
