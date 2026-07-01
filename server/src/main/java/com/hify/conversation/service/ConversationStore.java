package com.hify.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.event.TokenUsedEvent;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.conversation.config.ConversationProperties;
import com.hify.conversation.constant.MessageRole;
import com.hify.conversation.entity.Conversation;
import com.hify.conversation.entity.Message;
import com.hify.conversation.mapper.ConversationMapper;
import com.hify.conversation.mapper.MessageMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 会话/消息落库与读取。所有 @Transactional 收口在此（与编排层 ConversationService 分离，
 * 确保 LLM 调用发生在两个独立事务之间、不被任何事务包裹——CLAUDE.md 硬规则 6）。
 */
@Service
public class ConversationStore {

    private static final int TITLE_MAX = 100;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ConversationProperties props;
    private final ApplicationEventPublisher publisher;

    public ConversationStore(ConversationMapper conversationMapper, MessageMapper messageMapper,
                             ConversationProperties props, ApplicationEventPublisher publisher) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.props = props;
        this.publisher = publisher;
    }

    /** 事务A：解析/新建会话 + 落 user 消息 + 同事务读最近窗口，返回 (cid, window, userMessageId, newConversation)。 */
    @Transactional
    public TurnContext openTurn(Long appId, Long conversationId, Long userId, String userContent) {
        boolean isNew = (conversationId == null);
        Long cid;
        if (isNew) {
            Conversation c = new Conversation();
            c.setAppId(appId);
            c.setUserId(userId);
            c.setTitle(titleFrom(userContent));
            conversationMapper.insert(c);
            cid = c.getId();
        } else {
            assertOwned(conversationId, userId);
            cid = conversationId;
        }
        Message user = new Message();
        user.setConversationId(cid);
        user.setRole(MessageRole.USER.value());
        user.setContent(userContent.strip());
        messageMapper.insert(user);
        return new TurnContext(cid, readWindow(cid), user.getId(), isNew);
    }

    /**
     * 流式失败时清理孤儿：删本轮 user 消息；若本轮新建会话则连会话一并删（软删 @TableLogic）。
     * 仅由真失败路径调用，取消/正常完成不调。
     */
    @Transactional
    public void cleanupFailedTurn(Long conversationId, Long userMessageId, boolean newConversation) {
        messageMapper.deleteById(userMessageId);
        if (newConversation) {
            conversationMapper.deleteById(conversationId);
        }
    }

    /** 读最近 2N+1 条（N 轮历史 + 当前消息），时间正序。@TableLogic 自动加 deleted=false。 */
    private List<Message> readWindow(Long conversationId) {
        int limit = 2 * props.memory().windowRounds() + 1;
        List<Message> desc = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getId)
                .last("limit " + limit));
        Collections.reverse(desc);
        return desc;
    }

    /**
     * 事务B：落 assistant 消息 + touch 会话 update_time + 发 TokenUsedEvent 计量。
     * 事件在**本事务内**发布，使 usage 的 {@code @TransactionalEventListener(AFTER_COMMIT)} 在事务提交后触发
     * （若在无事务的编排层发布会被丢弃）。失败/取消的轮不走到这里，故天然不计量（决策 E）。
     * userId/appId/modelId 仅用于计量事件，不落 message 表。
     */
    @Transactional
    public Message appendAssistant(Long conversationId, String content, int promptTokens, int completionTokens,
                                   Long userId, Long appId, Long modelId) {
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setRole(MessageRole.ASSISTANT.value());
        m.setContent(content);
        m.setPromptTokens(promptTokens);
        m.setCompletionTokens(completionTokens);
        messageMapper.insert(m);
        Conversation touch = new Conversation();
        touch.setId(conversationId);
        conversationMapper.updateById(touch); // update_time 由 infra MetaObjectHandler 自动填充
        publisher.publishEvent(new TokenUsedEvent(userId, appId, modelId, promptTokens, completionTokens));
        return m;
    }

    /** 读：本人在某 app 下最近活跃会话（update_time desc，cap recent-limit）。@TableLogic 自动加 deleted=false。 */
    public List<Conversation> listConversations(Long appId, Long userId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getAppId, appId)
                .orderByDesc(Conversation::getUpdateTime)
                .last("limit " + props.list().recentLimit()));
    }

    /** 读：列出某会话消息（按 id 升序）。会话非本人/不存在抛 404。 */
    public List<Message> listMessages(Long conversationId, Long userId) {
        assertOwned(conversationId, userId);
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getId));
    }

    /**
     * 软删会话（按 user_id 作用域，幂等）+ 级联软删其全部消息。
     * 0 行命中（非本人/已删）不报错、不级联——满足 DELETE 幂等（api-standards §2.2）且不泄露存在性。
     * @TableLogic 使 mapper.delete 实为 UPDATE deleted=true WHERE ... AND deleted=false。
     */
    @Transactional
    public void deleteConversation(Long conversationId, Long userId) {
        int rows = conversationMapper.delete(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId));
        if (rows > 0) {
            messageMapper.delete(new LambdaQueryWrapper<Message>()
                    .eq(Message::getConversationId, conversationId));
        }
    }

    private void assertOwned(Long conversationId, Long userId) {
        Conversation c = conversationMapper.selectById(conversationId);
        if (c == null || !userId.equals(c.getUserId())) {
            throw new BizException(CommonError.NOT_FOUND, "会话不存在");
        }
    }

    private static String titleFrom(String content) {
        String t = content.strip();
        return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX);
    }
}
