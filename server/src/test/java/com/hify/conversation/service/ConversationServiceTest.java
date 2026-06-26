package com.hify.conversation.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.dto.AppConfig;
import com.hify.app.api.dto.AppRuntimeView;
import com.hify.common.exception.BizException;
import com.hify.conversation.constant.ConversationError;
import com.hify.conversation.constant.MessageRole;
import com.hify.conversation.dto.SendMessageResponse;
import com.hify.conversation.entity.Message;
import com.hify.infra.security.CurrentUser;
import com.hify.provider.api.ProviderFacade;
import com.hify.provider.constant.ProviderError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
                .thenReturn(Optional.of(new AppRuntimeView(7L, 5L, new AppConfig(systemPrompt))));
        when(providerFacade.getChatClient(eq(5L))).thenReturn(chatClient);
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
        when(store.openTurn(eq(7L), eq(null), eq(42L), eq("你好"))).thenReturn(100L);
        when(chatInvoker.invoke(eq(chatClient), eq("你是客服"), eq("你好")))
                .thenReturn(new LlmReply("你好，我是助手", 12, 8));
        when(store.appendAssistant(eq(100L), eq("你好，我是助手"), eq(12), eq(8)))
                .thenReturn(savedAssistant());

        SendMessageResponse resp = service.send(7L, null, "你好", member);

        // 配额检查在落库前
        InOrder order = inOrder(quotaGuard, store, chatInvoker);
        order.verify(quotaGuard).check(42L, 7L);
        order.verify(store).openTurn(7L, null, 42L, "你好");
        order.verify(chatInvoker).invoke(chatClient, "你是客服", "你好");
        order.verify(store).appendAssistant(100L, "你好，我是助手", 12, 8);

        assertEquals(100L, resp.conversationId());
        assertEquals(200L, resp.message().id());
        assertEquals("assistant", resp.message().role());
        assertEquals("你好，我是助手", resp.message().content());
        assertEquals(12, resp.message().promptTokens());
    }

    @Test
    void send_单轮_只把当前消息喂模型_不含历史() {
        stubRunnableApp(null);
        when(store.openTurn(any(), any(), any(), any())).thenReturn(100L);
        when(chatInvoker.invoke(any(), any(), any())).thenReturn(new LlmReply("ok", 1, 1));
        when(store.appendAssistant(any(), any(), anyInt(), anyInt())).thenReturn(savedAssistant());

        service.send(7L, 100L, "第二句", member);

        ArgumentCaptor<String> sys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> usr = ArgumentCaptor.forClass(String.class);
        verify(chatInvoker).invoke(eq(chatClient), sys.capture(), usr.capture());
        assertEquals(null, sys.getValue());        // systemPrompt 为 null 透传
        assertEquals("第二句", usr.getValue());     // 只当前消息，无历史
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
                .thenReturn(Optional.of(new AppRuntimeView(7L, 5L, new AppConfig(null))));
        when(store.openTurn(any(), any(), any(), any())).thenReturn(100L);
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
        when(store.openTurn(any(), any(), any(), any())).thenReturn(100L);
        when(chatInvoker.invoke(any(), any(), any()))
                .thenThrow(new BizException(ProviderError.PROVIDER_UNAVAILABLE));

        BizException ex = assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));
        assertEquals(ProviderError.PROVIDER_UNAVAILABLE, ex.errorCode());
        verify(store, never()).appendAssistant(any(), any(), anyInt(), anyInt());
    }

    @Test
    void history_委托store_按当前用户过滤() {
        Message m = savedAssistant();
        when(store.listMessages(eq(100L), eq(42L))).thenReturn(java.util.List.of(m));

        var list = service.history(100L, member);

        assertEquals(1, list.size());
        assertEquals(200L, list.get(0).id());
        verify(store).listMessages(100L, 42L);
    }
}
