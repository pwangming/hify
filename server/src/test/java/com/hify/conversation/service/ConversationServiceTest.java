package com.hify.conversation.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.AppRuntimeView;
import com.hify.common.exception.BizException;
import com.hify.conversation.config.ConversationProperties;
import com.hify.conversation.constant.ConversationError;
import com.hify.conversation.constant.MessageRole;
import com.hify.conversation.dto.ConversationView;
import com.hify.conversation.dto.MessageSource;
import com.hify.conversation.dto.MessageToolCall;
import com.hify.conversation.dto.SendMessageResponse;
import com.hify.conversation.entity.Conversation;
import com.hify.conversation.entity.Message;
import com.hify.infra.security.CurrentUser;
import com.hify.knowledge.api.KnowledgeFacade;
import com.hify.knowledge.api.RetrievedChunk;
import com.hify.provider.api.ProviderFacade;
import com.hify.provider.constant.ProviderError;
import com.hify.tool.api.ToolFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    private AppFacade appFacade;
    private ProviderFacade providerFacade;
    private ChatInvoker chatInvoker;
    private ConversationStore store;
    private QuotaGuard quotaGuard;
    private KnowledgeFacade knowledgeFacade;
    private ToolFacade toolFacade;
    private AgentChatService agentChatService;
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
        knowledgeFacade = mock(KnowledgeFacade.class);
        toolFacade = mock(ToolFacade.class);
        agentChatService = mock(AgentChatService.class);
        service = new ConversationService(appFacade, providerFacade, chatInvoker, store, quotaGuard, knowledgeFacade,
                toolFacade, agentChatService, new ConversationProperties(new ConversationProperties.Memory(10),
                        new ConversationProperties.ListProps(50), 10));
    }

    private void stubRunnableApp(String systemPrompt) {
        stubRunnableApp(systemPrompt, List.of());
    }

    private void stubRunnableApp(String systemPrompt, List<Long> datasetIds) {
        when(appFacade.findRunnableChatApp(eq(7L)))
                .thenReturn(Optional.of(new AppRuntimeView(7L, 5L, systemPrompt, datasetIds, false)));
        when(providerFacade.getChatClient(eq(5L))).thenReturn(chatClient);
    }

    private void stubAgentRunnableApp(String systemPrompt) {
        when(appFacade.findRunnableChatApp(eq(7L)))
                .thenReturn(Optional.of(new AppRuntimeView(7L, 5L, systemPrompt, List.of(), true)));
        when(providerFacade.getChatClient(eq(5L))).thenReturn(chatClient);
    }

    private AppRuntimeView runnableChatAppBoundTo(List<Long> datasetIds) {
        return new AppRuntimeView(7L, 5L, "你是客服", datasetIds, false);
    }

    private void stubTurnAndReplyFor(String content) {
        when(store.openTurn(eq(7L), eq(null), eq(42L), eq(content)))
                .thenReturn(new TurnContext(100L, List.of(userMsg(content)), 300L, true));
        when(chatInvoker.invoke(any(), any(), any())).thenReturn(new LlmReply("答案", 12, 8));
        when(store.appendAssistant(any(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(savedAssistant());
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

    private void stubTurnAndReply() {
        when(store.openTurn(eq(7L), eq(null), eq(42L), eq("退货政策")))
                .thenReturn(new TurnContext(100L, List.of(userMsg("退货政策")), 300L, true));
        when(chatInvoker.invoke(any(), any(), any())).thenReturn(new LlmReply("答案", 12, 8));
        when(store.appendAssistant(any(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(savedAssistant());
    }

    @Test
    void send_新会话_三段时序_配额先行_返回assistant视图() {
        stubRunnableApp("你是客服");
        List<Message> window = List.of(userMsg("你好"));
        when(store.openTurn(eq(7L), eq(null), eq(42L), eq("你好")))
                .thenReturn(new TurnContext(100L, window, 300L, true));
        when(chatInvoker.invoke(eq(chatClient), eq("你是客服"), eq(window)))
                .thenReturn(new LlmReply("你好，我是助手", 12, 8));
        when(store.appendAssistant(eq(100L), eq("你好，我是助手"), eq(12), eq(8), eq(42L), eq(7L), eq(5L), any()))
                .thenReturn(savedAssistant());

        SendMessageResponse resp = service.send(7L, null, "你好", member);

        // 配额检查在落库前；窗口喂模型在两次 store 写之间
        InOrder order = inOrder(quotaGuard, store, chatInvoker);
        order.verify(quotaGuard).check(42L, 7L);
        order.verify(store).openTurn(7L, null, 42L, "你好");
        order.verify(chatInvoker).invoke(chatClient, "你是客服", window);
        order.verify(store).appendAssistant(100L, "你好，我是助手", 12, 8, 42L, 7L, 5L, List.of());

        assertEquals(100L, resp.conversationId());
        assertEquals(200L, resp.message().id());
        assertEquals("assistant", resp.message().role());
        assertEquals("你好，我是助手", resp.message().content());
        assertEquals(12, resp.message().promptTokens());
    }

    @Test
    void send_配额超额_14001先行_不落库不调模型() {
        // 配额锚点在最前：checkQuota 抛出即中止，openTurn/invoke 都不发生
        org.mockito.Mockito.doThrow(new BizException(com.hify.usage.constant.UsageError.QUOTA_EXCEEDED))
                .when(quotaGuard).check(42L, 7L);

        BizException ex = assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));
        assertEquals(com.hify.usage.constant.UsageError.QUOTA_EXCEEDED, ex.errorCode());
        verify(appFacade, never()).findRunnableChatApp(any());
        verify(store, never()).openTurn(any(), any(), any(), any());
        verify(chatInvoker, never()).invoke(any(), any(), any());
    }

    @Test
    void send_多轮_把store返回的窗口整体喂模型() {
        stubRunnableApp(null);
        List<Message> window = List.of(userMsg("第一句"), assistantMsg("回复一"), userMsg("第二句"));
        when(store.openTurn(any(), any(), any(), any())).thenReturn(new TurnContext(100L, window, 300L, false));
        when(chatInvoker.invoke(any(), any(), any())).thenReturn(new LlmReply("ok", 1, 1));
        when(store.appendAssistant(any(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(), any())).thenReturn(savedAssistant());

        service.send(7L, 100L, "第二句", member);

        // 窗口原样透传给 invoker（含历史，末位当前消息），systemPrompt 为 null 透传
        verify(chatInvoker).invoke(eq(chatClient), eq(null), eq(window));
    }

    @Test
    void send_agent开启_走AgentChatService并用9参落轨迹_不调普通chatInvoker() {
        stubAgentRunnableApp("你是客服");
        MessageToolCall trace = new MessageToolCall("http_request", "{\"url\":\"x\"}", "HTTP 200");
        List<ToolCallback> callbacks = List.of(mock(ToolCallback.class));
        when(store.openTurn(eq(7L), eq(null), eq(42L), eq("查接口")))
                .thenReturn(new TurnContext(100L, List.of(userMsg("查接口")), 300L, true));
        when(toolFacade.getBuiltinToolCallbacks()).thenReturn(callbacks);
        when(agentChatService.run(eq(chatClient), eq("你是客服"), any(), eq(callbacks)))
                .thenReturn(new AgentReply("答案", 7, 8, List.of(trace)));
        when(store.appendAssistant(eq(100L), eq("答案"), eq(7), eq(8),
                eq(42L), eq(7L), eq(5L), eq(List.of()), eq(List.of(trace))))
                .thenReturn(savedAssistant());

        SendMessageResponse resp = service.send(7L, null, "查接口", member);

        assertEquals(100L, resp.conversationId());
        verify(agentChatService).run(eq(chatClient), eq("你是客服"), any(), eq(callbacks));
        verify(store).appendAssistant(100L, "答案", 7, 8,
                42L, 7L, 5L, List.of(), List.of(trace));
        verify(chatInvoker, never()).invoke(any(), any(), any());
    }

    @Test
    void send_agent关闭_走普通chatInvoker_不调AgentChatService() {
        stubRunnableApp("你是客服");
        stubTurnAndReplyFor("你好");

        service.send(7L, null, "你好", member);

        verify(chatInvoker).invoke(eq(chatClient), eq("你是客服"), any());
        verify(agentChatService, never()).run(any(), any(), any(), any());
    }

    @Test
    void sendStream_agent开启_同步抛17002且不落turn() {
        stubAgentRunnableApp("你是客服");

        BizException ex = assertThrows(BizException.class,
                () -> service.sendStream(7L, null, "你好", member));

        assertEquals(ConversationError.AGENT_STREAM_UNSUPPORTED, ex.errorCode());
        verify(store, never()).openTurn(any(), any(), any(), any());
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
    void send_模型不可用_透传12002_清理孤儿turn() {
        when(appFacade.findRunnableChatApp(eq(7L)))
                .thenReturn(Optional.of(new AppRuntimeView(7L, 5L, null, List.of(), false)));
        when(store.openTurn(any(), any(), any(), any()))
                .thenReturn(new TurnContext(100L, List.of(userMsg("你好")), 300L, true));
        when(providerFacade.getChatClient(eq(5L)))
                .thenThrow(new BizException(ProviderError.MODEL_NOT_USABLE));

        BizException ex = assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));
        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
        verify(store).openTurn(7L, null, 42L, "你好"); // 事务A 已发生
        verify(store).cleanupFailedTurn(100L, 300L, true); // 新会话：删会话+user消息，与流式语义一致
        verify(store, never()).appendAssistant(any(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(), any());
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
        verify(store).cleanupFailedTurn(100L, 300L, true);
        verify(store, never()).appendAssistant(any(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void send_续聊失败_只删user消息不删会话() {
        stubRunnableApp(null);
        when(store.openTurn(any(), any(), any(), any()))
                .thenReturn(new TurnContext(100L, List.of(userMsg("继续")), 300L, false));
        when(chatInvoker.invoke(any(), any(), any()))
                .thenThrow(new BizException(ProviderError.PROVIDER_UNAVAILABLE));

        assertThrows(BizException.class, () -> service.send(7L, 100L, "继续", member));
        verify(store).cleanupFailedTurn(100L, 300L, false);
    }

    @Test
    void send_清理孤儿失败_不吞原始异常() {
        stubRunnableApp(null);
        when(store.openTurn(any(), any(), any(), any()))
                .thenReturn(new TurnContext(100L, List.of(userMsg("你好")), 300L, true));
        when(chatInvoker.invoke(any(), any(), any()))
                .thenThrow(new BizException(ProviderError.PROVIDER_UNAVAILABLE));
        doThrow(new RuntimeException("db down")).when(store).cleanupFailedTurn(any(), any(), anyBoolean());

        BizException ex = assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));
        assertEquals(ProviderError.PROVIDER_UNAVAILABLE, ex.errorCode()); // 原始错误必须原样透传
    }

    @Test
    void 绑库_检索命中_系统提示词尾部拼参考资料() {
        stubRunnableApp("你是客服", List.of(9L));
        stubTurnAndReply();
        when(knowledgeFacade.retrieve(List.of(9L), "退货政策"))
                .thenReturn(List.of(new RetrievedChunk(1L, 2L, "a.txt", "退货需在7天内", 0.83)));
        service.send(7L, null, "退货政策", member);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatInvoker).invoke(eq(chatClient), prompt.capture(), any());
        assertTrue(prompt.getValue().startsWith("你是客服"));
        assertTrue(prompt.getValue().contains("参考资料"));
        assertTrue(prompt.getValue().contains("[1] 退货需在7天内"));
    }

    @Test
    void 绑库_原提示词为空_直接以参考资料开头() {
        stubRunnableApp(null, List.of(9L));
        stubTurnAndReply();
        when(knowledgeFacade.retrieve(List.of(9L), "退货政策"))
                .thenReturn(List.of(new RetrievedChunk(1L, 2L, "a.txt", "退货需在7天内", 0.83)));
        service.send(7L, null, "退货政策", member);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatInvoker).invoke(eq(chatClient), prompt.capture(), any());
        assertTrue(prompt.getValue().startsWith("请优先依据下列参考资料"));
    }

    @Test
    void 绑库_检索抛异常_降级用原提示词且正常回答() {
        stubRunnableApp("你是客服", List.of(9L));
        stubTurnAndReply();
        when(knowledgeFacade.retrieve(any(), any())).thenThrow(new RuntimeException("供应商超时"));
        service.send(7L, null, "退货政策", member);
        verify(chatInvoker).invoke(eq(chatClient), eq("你是客服"), any());
    }

    @Test
    void 绑库_命中为空_提示词原样不拼空壳() {
        stubRunnableApp("你是客服", List.of(9L));
        stubTurnAndReply();
        when(knowledgeFacade.retrieve(List.of(9L), "退货政策")).thenReturn(List.of());
        service.send(7L, null, "退货政策", member);
        verify(chatInvoker).invoke(eq(chatClient), eq("你是客服"), any());
    }

    @Test
    void 未绑库_不调检索() {
        stubRunnableApp("你是客服");
        stubTurnAndReply();
        service.send(7L, null, "退货政策", member);
        verifyNoInteractions(knowledgeFacade);
    }

    @Test
    void send_boundApp_hit_capturesSourcesWithTruncatedPreview() {
        var app = runnableChatAppBoundTo(List.of(100L));
        when(appFacade.findRunnableChatApp(7L)).thenReturn(Optional.of(app));
        when(providerFacade.getChatClient(eq(5L))).thenReturn(chatClient);
        stubTurnAndReplyFor("问题");
        when(knowledgeFacade.retrieve(eq(List.of(100L)), eq("问题")))
                .thenReturn(List.of(new RetrievedChunk(10L, 20L, "手册.pdf", "0123456789ABCDEF", 0.9)));

        service.send(7L, null, "问题", member);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSource>> cap = ArgumentCaptor.forClass(List.class);
        verify(store).appendAssistant(anyLong(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(),
                cap.capture());
        List<MessageSource> src = cap.getValue();
        assertEquals(1, src.size());
        assertEquals(10L, src.get(0).chunkId());
        assertEquals("手册.pdf", src.get(0).documentName());
        assertEquals(0.9, src.get(0).score());
        assertEquals("0123456789", src.get(0).preview());
    }

    @Test
    void send_unboundApp_capturesEmptySources() {
        var app = runnableChatAppBoundTo(List.of());
        when(appFacade.findRunnableChatApp(7L)).thenReturn(Optional.of(app));
        when(providerFacade.getChatClient(eq(5L))).thenReturn(chatClient);
        stubTurnAndReplyFor("问题");

        service.send(7L, null, "问题", member);

        verify(store).appendAssistant(anyLong(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(),
                eq(List.of()));
        verifyNoInteractions(knowledgeFacade);
    }

    @Test
    void send_retrieveThrows_degradesWithEmptySources() {
        var app = runnableChatAppBoundTo(List.of(100L));
        when(appFacade.findRunnableChatApp(7L)).thenReturn(Optional.of(app));
        when(providerFacade.getChatClient(eq(5L))).thenReturn(chatClient);
        stubTurnAndReplyFor("问题");
        when(knowledgeFacade.retrieve(any(), any())).thenThrow(new RuntimeException("embedding 未配"));

        service.send(7L, null, "问题", member);

        verify(store).appendAssistant(anyLong(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(),
                eq(List.of()));
    }

    @Test
    void send_hitEmpty_capturesEmptySources() {
        var app = runnableChatAppBoundTo(List.of(100L));
        when(appFacade.findRunnableChatApp(7L)).thenReturn(Optional.of(app));
        when(providerFacade.getChatClient(eq(5L))).thenReturn(chatClient);
        stubTurnAndReplyFor("问题");
        when(knowledgeFacade.retrieve(any(), any())).thenReturn(List.of());

        service.send(7L, null, "问题", member);

        verify(store).appendAssistant(anyLong(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(),
                eq(List.of()));
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

    @Test
    void deleteConversation_委托store_传当前用户() {
        service.deleteConversation(100L, member);
        verify(store).deleteConversation(100L, 42L);
    }

    @Test
    void renameConversation_委托store_传当前用户与标题() {
        service.renameConversation(100L, "新名", member);
        verify(store).renameConversation(100L, 42L, "新名");
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

    private void stubStreamOneDelta(String text) {
        when(store.openTurn(eq(7L), eq(null), eq(42L), any()))
                .thenReturn(new TurnContext(100L, List.of(userMsg("问题")), 300L, true));
        when(providerFacade.getChatClient(eq(5L))).thenReturn(chatClient);
        when(chatInvoker.invokeStream(eq(chatClient), any(), any()))
                .thenReturn(reactor.core.publisher.Flux.just(chunkWithUsage(text, 12, 8)));
        when(store.appendAssistant(any(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(savedAssistant());
    }

    private static int indexOfType(List<StreamEvent> events, Class<? extends StreamEvent> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void sendStream_boundHit_emitsSourcesAfterMetaBeforeDelta() {
        var app = runnableChatAppBoundTo(List.of(100L));
        when(appFacade.findRunnableChatApp(7L)).thenReturn(Optional.of(app));
        when(knowledgeFacade.retrieve(any(), any()))
                .thenReturn(List.of(new RetrievedChunk(10L, 20L, "手册.pdf", "命中内容", 0.9)));
        stubStreamOneDelta("你好");

        List<StreamEvent> events = service.sendStream(7L, null, "问题", member).collectList().block();

        assertTrue(events.get(0) instanceof StreamEvent.Meta);
        assertTrue(events.get(1) instanceof StreamEvent.Sources);
        assertEquals(1, ((StreamEvent.Sources) events.get(1)).sources().size());
        assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.Delta));
        int srcIdx = indexOfType(events, StreamEvent.Sources.class);
        int deltaIdx = indexOfType(events, StreamEvent.Delta.class);
        assertTrue(srcIdx < deltaIdx);
    }

    @Test
    void sendStream_unbound_emitsNoSourcesEvent() {
        var app = runnableChatAppBoundTo(List.of());
        when(appFacade.findRunnableChatApp(7L)).thenReturn(Optional.of(app));
        stubStreamOneDelta("你好");

        List<StreamEvent> events = service.sendStream(7L, null, "问题", member).collectList().block();

        assertTrue(events.stream().noneMatch(e -> e instanceof StreamEvent.Sources));
    }

    @Test
    void sendStream_增量吐字_结束落全文与usage_发Done() {
        stubRunnableApp("你是客服");
        when(store.openTurn(eq(7L), eq(null), eq(42L), eq("你好")))
                .thenReturn(new TurnContext(100L, java.util.List.of(userMsg("你好")), 300L, true));
        when(chatInvoker.invokeStream(eq(chatClient), eq("你是客服"), any()))
                .thenReturn(reactor.core.publisher.Flux.just(chunk("你好，"), chunkWithUsage("我是助手", 12, 8)));
        when(store.appendAssistant(eq(100L), eq("你好，我是助手"), eq(12), eq(8), eq(42L), eq(7L), eq(5L), any()))
                .thenReturn(savedAssistant());

        reactor.test.StepVerifier.create(service.sendStream(7L, null, "你好", member))
                .expectNext(new StreamEvent.Meta(100L))
                .expectNext(new StreamEvent.Delta("你好，"))
                .expectNext(new StreamEvent.Delta("我是助手"))
                .expectNextMatches(e -> e instanceof StreamEvent.Done d
                        && d.conversationId() == 100L && d.messageId() == 200L
                        && d.promptTokens() == 12 && d.completionTokens() == 8)
                .verifyComplete();
        verify(store).appendAssistant(100L, "你好，我是助手", 12, 8, 42L, 7L, 5L, List.of());
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
                .expectNext(new StreamEvent.Meta(100L))
                .expectNext(new StreamEvent.Delta("半"))
                .expectErrorMatches(e -> e instanceof BizException b
                        && b.errorCode() == ProviderError.PROVIDER_UNAVAILABLE)
                .verify();
        // 半截失败也清理孤儿（本回合新建会话）
        verify(store).cleanupFailedTurn(100L, 300L, true);
        verify(store, never()).appendAssistant(any(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(), any());
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
                .expectNext(new StreamEvent.Meta(100L))
                .expectErrorMatches(e -> e instanceof BizException b
                        && b.errorCode() == ProviderError.PROVIDER_UNAVAILABLE)
                .verify();
        verify(store).cleanupFailedTurn(100L, 300L, true);
        verify(store, never()).appendAssistant(any(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(), any());
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
                .expectNext(new StreamEvent.Meta(100L))
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
        when(store.appendAssistant(any(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(), any())).thenReturn(savedAssistant());

        reactor.test.StepVerifier.create(service.sendStream(7L, null, "你好", member))
                .expectNext(new StreamEvent.Meta(100L))
                .expectNext(new StreamEvent.Delta("答案"))
                .expectNextMatches(e -> e instanceof StreamEvent.Done)
                .verifyComplete();
        verify(store, never()).cleanupFailedTurn(any(), any(), anyBoolean());
    }

    @Test
    void sendStream_取消_不清理孤儿也不落assistant() {
        // 用户切会话=取消（cancel 信号，非 onError）：不得触发 cleanupFailedTurn，也不落 assistant
        stubRunnableApp(null);
        when(store.openTurn(any(), any(), any(), any()))
                .thenReturn(new TurnContext(100L, java.util.List.of(userMsg("你好")), 300L, true));
        when(chatInvoker.invokeStream(any(), any(), any()))
                .thenReturn(reactor.core.publisher.Flux.never()); // 不吐字、不结束

        reactor.test.StepVerifier.create(service.sendStream(7L, null, "你好", member))
                .expectSubscription()
                .thenCancel()
                .verify();
        verify(store, never()).cleanupFailedTurn(any(), any(), anyBoolean());
        verify(store, never()).appendAssistant(any(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(), any());
    }
}
