package com.hify.conversation.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.AppRuntimeView;
import com.hify.common.exception.BizException;
import com.hify.conversation.constant.ConversationError;
import com.hify.conversation.dto.ConversationView;
import com.hify.conversation.dto.MessageView;
import com.hify.conversation.dto.SendMessageResponse;
import com.hify.conversation.entity.Message;
import com.hify.infra.security.CurrentUser;
import com.hify.provider.api.ProviderFacade;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 单轮聊天编排。本类**不带 @Transactional**：事务边界全在 ConversationStore，
 * LLM 调用（chatInvoker.invoke）夹在两个事务之间、不被任何事务包裹（CLAUDE.md 硬规则 6）。
 * 单轮：组 prompt 只含 systemPrompt + 当前消息，不读历史。
 */
@Service
public class ConversationService {

    private final AppFacade appFacade;
    private final ProviderFacade providerFacade;
    private final ChatInvoker chatInvoker;
    private final ConversationStore store;
    private final QuotaGuard quotaGuard;

    public ConversationService(AppFacade appFacade, ProviderFacade providerFacade,
                               ChatInvoker chatInvoker, ConversationStore store, QuotaGuard quotaGuard) {
        this.appFacade = appFacade;
        this.providerFacade = providerFacade;
        this.chatInvoker = chatInvoker;
        this.store = store;
        this.quotaGuard = quotaGuard;
    }

    public SendMessageResponse send(Long appId, Long conversationId, String content, CurrentUser current) {
        // 1) 配额锚点（本轮放行）
        quotaGuard.check(current.userId(), appId);
        // 2) 校验应用可对话（读，无事务）
        AppRuntimeView app = appFacade.findRunnableChatApp(appId)
                .orElseThrow(() -> new BizException(ConversationError.APP_NOT_RUNNABLE));
        // 3) 事务A：建/取会话 + 落 user 消息 + 读窗口
        TurnContext turn = store.openTurn(appId, conversationId, current.userId(), content);
        Long cid = turn.conversationId();
        // 4) 取 ChatClient（不可用抛 12002）并调用——事务外，喂历史窗口
        ChatClient chatClient = providerFacade.getChatClient(app.modelId());
        LlmReply reply = chatInvoker.invoke(chatClient, app.systemPrompt(), turn.window());
        // 5) 事务B：落 assistant 消息
        Message saved = store.appendAssistant(cid, reply.content(), reply.promptTokens(), reply.completionTokens());
        return new SendMessageResponse(cid, toView(saved));
    }

    public List<MessageView> history(Long conversationId, CurrentUser current) {
        return store.listMessages(conversationId, current.userId()).stream()
                .map(ConversationService::toView)
                .toList();
    }

    public List<ConversationView> listConversations(Long appId, CurrentUser current) {
        return store.listConversations(appId, current.userId()).stream()
                .map(c -> new ConversationView(c.getId(), c.getTitle(), c.getUpdateTime()))
                .toList();
    }

    private static MessageView toView(Message m) {
        return new MessageView(m.getId(), m.getRole(), m.getContent(),
                m.getPromptTokens(), m.getCompletionTokens(), m.getCreateTime());
    }
}
