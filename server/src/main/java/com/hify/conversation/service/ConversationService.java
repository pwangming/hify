package com.hify.conversation.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.AppRuntimeView;
import com.hify.common.exception.BizException;
import com.hify.conversation.config.ConversationProperties;
import com.hify.conversation.constant.ConversationError;
import com.hify.conversation.dto.ConversationView;
import com.hify.conversation.dto.MessageSource;
import com.hify.conversation.dto.MessageView;
import com.hify.conversation.dto.SendMessageResponse;
import com.hify.conversation.entity.Message;
import com.hify.infra.security.CurrentUser;
import com.hify.knowledge.api.KnowledgeFacade;
import com.hify.knowledge.api.RetrievedChunk;
import com.hify.provider.api.ProviderFacade;
import com.hify.tool.api.ToolFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 多轮聊天编排。本类**不带 @Transactional**：事务边界全在 ConversationStore，
 * LLM 调用（chatInvoker.invoke）夹在两个事务之间、不被任何事务包裹（CLAUDE.md 硬规则 6）。
 * 多轮：openTurn 在事务内返回最近窗口（TurnContext.window()，含历史 + 当前消息），整窗口喂模型。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** 检索注入的提示词头（K4；引用来源展示留下轮，此处只拼内容不拼出处）。 */
    private static final String KNOWLEDGE_PROMPT_HEADER =
            "请优先依据下列参考资料回答用户问题；资料未覆盖时可依据自身知识回答。\n参考资料：";

    private final AppFacade appFacade;
    private final ProviderFacade providerFacade;
    private final ChatInvoker chatInvoker;
    private final ConversationStore store;
    private final QuotaGuard quotaGuard;
    private final KnowledgeFacade knowledgeFacade;
    private final ToolFacade toolFacade;
    private final AgentChatService agentChatService;
    private final ConversationProperties props;

    public ConversationService(AppFacade appFacade, ProviderFacade providerFacade,
                               ChatInvoker chatInvoker, ConversationStore store, QuotaGuard quotaGuard,
                               KnowledgeFacade knowledgeFacade, ToolFacade toolFacade,
                               AgentChatService agentChatService, ConversationProperties props) {
        this.appFacade = appFacade;
        this.providerFacade = providerFacade;
        this.chatInvoker = chatInvoker;
        this.store = store;
        this.quotaGuard = quotaGuard;
        this.knowledgeFacade = knowledgeFacade;
        this.toolFacade = toolFacade;
        this.agentChatService = agentChatService;
        this.props = props;
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
        try {
            // 4) 取 ChatClient（不可用抛 12002）并调用——事务外，喂历史窗口
            ChatClient chatClient = providerFacade.getChatClient(app.modelId());
            Message saved;
            if (app.agentEnabled()) {
                AgentReply reply = agentChatService.run(chatClient, app.systemPrompt(), turn.window(),
                        toolFacade.getBuiltinToolCallbacks());
                saved = store.appendAssistant(cid, reply.content(), reply.promptTokens(), reply.completionTokens(),
                        current.userId(), appId, app.modelId(), List.of(), reply.toolCalls());
            } else {
                Augmented aug = augmentWithKnowledge(app, content);
                LlmReply reply = chatInvoker.invoke(chatClient, aug.prompt(), turn.window());
                saved = store.appendAssistant(cid, reply.content(), reply.promptTokens(), reply.completionTokens(),
                        current.userId(), appId, app.modelId(), aug.sources());
            }
            return new SendMessageResponse(cid, toView(saved));
        } catch (RuntimeException e) {
            // 修缮轮 D2：失败清孤儿（新会话删会话+user消息；续聊只删user消息），与 sendStream 语义对齐
            try {
                store.cleanupFailedTurn(cid, turn.userMessageId(), turn.newConversation());
            } catch (RuntimeException cleanupEx) {
                log.warn("同步端点孤儿清理失败 conversationId={}", cid, cleanupEx);
            }
            throw e;
        }
    }

    public Flux<StreamEvent> sendStream(Long appId, Long conversationId, String content, CurrentUser current) {
        quotaGuard.check(current.userId(), appId);
        AppRuntimeView app = appFacade.findRunnableChatApp(appId)
                .orElseThrow(() -> new BizException(ConversationError.APP_NOT_RUNNABLE));
        if (app.agentEnabled()) {
            throw new BizException(ConversationError.AGENT_STREAM_UNSUPPORTED);
        }
        TurnContext turn = store.openTurn(appId, conversationId, current.userId(), content);
        Long cid = turn.conversationId();
        ChatClient chatClient = providerFacade.getChatClient(app.modelId());
        Augmented aug = augmentWithKnowledge(app, content);

        StringBuilder buf = new StringBuilder();
        int[] usage = {0, 0}; // [0]=promptTokens [1]=completionTokens，取流中最后一个非空值

        Flux<StreamEvent> deltas = chatInvoker.invokeStream(chatClient, aug.prompt(), turn.window())
                .doOnNext(cr -> {
                    Usage u = cr.getMetadata() != null ? cr.getMetadata().getUsage() : null;
                    if (u != null) {
                        if (u.getPromptTokens() != null) usage[0] = u.getPromptTokens();
                        if (u.getCompletionTokens() != null) usage[1] = u.getCompletionTokens();
                    }
                    String t = textOf(cr);
                    if (t != null && !t.isEmpty()) buf.append(t);
                })
                .mapNotNull(cr -> {
                    String t = textOf(cr);
                    return (t == null || t.isEmpty()) ? null : new StreamEvent.Delta(t);
                });

        Mono<StreamEvent> done = Mono.<StreamEvent>fromCallable(() -> {
            Message saved = store.appendAssistant(cid, buf.toString(), usage[0], usage[1], // 事务B（内发 TokenUsedEvent）
                    current.userId(), appId, app.modelId(), aug.sources());
            return new StreamEvent.Done(cid, saved.getId(), usage[0], usage[1]);
        }).subscribeOn(Schedulers.boundedElastic());

        Flux<StreamEvent> sourcesFlux = aug.sources().isEmpty()
                ? Flux.empty()
                : Flux.just(new StreamEvent.Sources(aug.sources()));
        return Flux.concat(Mono.<StreamEvent>just(new StreamEvent.Meta(cid)), sourcesFlux, deltas, done)
                // 真失败(onError)即清理孤儿；取消(用户切会话)是 cancel 信号、不进 onErrorResume，故不会误删。
                // 清理是阻塞 JDBC，放 boundedElastic。
                .onErrorResume(err -> Mono.fromRunnable(() ->
                                store.cleanupFailedTurn(turn.conversationId(), turn.userMessageId(), turn.newConversation()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(cleanupEx -> Mono.empty())   // 清理失败不可盖住原始 LLM 错误
                        .then(Mono.<StreamEvent>error(err)));
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

    public void deleteConversation(Long conversationId, CurrentUser current) {
        store.deleteConversation(conversationId, current.userId());
    }

    public void renameConversation(Long conversationId, String title, CurrentUser current) {
        store.renameConversation(conversationId, current.userId(), title);
    }

    private static String textOf(ChatResponse cr) {
        return cr.getResults().isEmpty() ? null : cr.getResult().getOutput().getText();
    }

    /**
     * 绑库应用：按当前消息检索并把命中段拼进系统提示词尾部；未绑/无命中原样返回。
     * 检索任何失败（未配 embedding 模型/供应商超时熔断）降级继续答（spec 决策 5）——只记 warn，不影响本轮对话。
     * 调用点在事务 A 之后、LLM 调用之前（事务间隙，与「事务内禁外部 IO」红线一致）。
     */
    /** 检索产出：注入后的提示词 + 来源快照列表（未绑/降级/命中空时 sources 为空）。 */
    private record Augmented(String prompt, List<MessageSource> sources) {}

    private Augmented augmentWithKnowledge(AppRuntimeView app, String content) {
        if (app.datasetIds() == null || app.datasetIds().isEmpty()) {
            return new Augmented(app.systemPrompt(), List.of());
        }
        try {
            List<RetrievedChunk> chunks = knowledgeFacade.retrieve(app.datasetIds(), content);
            if (chunks.isEmpty()) {
                return new Augmented(app.systemPrompt(), List.of());
            }
            StringBuilder sb = new StringBuilder();
            if (StringUtils.hasText(app.systemPrompt())) {
                sb.append(app.systemPrompt()).append("\n\n");
            }
            sb.append(KNOWLEDGE_PROMPT_HEADER);
            List<MessageSource> sources = new java.util.ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                RetrievedChunk c = chunks.get(i);
                sb.append('\n').append('[').append(i + 1).append("] ").append(c.content());
                sources.add(new MessageSource(c.chunkId(), c.documentId(), c.documentName(),
                        c.score(), preview(c.content())));
            }
            return new Augmented(sb.toString(), List.copyOf(sources));
        } catch (Exception e) {
            log.warn("知识检索失败，本轮降级为无参考资料回答 appId={}", app.appId(), e);
            return new Augmented(app.systemPrompt(), List.of());
        }
    }

    /** 截断命中段为预览（不存全文）。长度取全局配置，按字符（code point 无关，中文按 char）截断。 */
    private String preview(String content) {
        int len = props.sourcePreviewLength();
        if (content == null) return "";
        return content.length() <= len ? content : content.substring(0, len);
    }

    private static MessageView toView(Message m) {
        return new MessageView(m.getId(), m.getRole(), m.getContent(),
                m.getPromptTokens(), m.getCompletionTokens(), m.getCreateTime(),
                m.getSources() == null ? List.of() : m.getSources(),
                m.getToolCalls() == null ? List.of() : m.getToolCalls());
    }
}
