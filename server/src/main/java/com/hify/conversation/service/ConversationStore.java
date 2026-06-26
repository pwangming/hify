package com.hify.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.conversation.constant.MessageRole;
import com.hify.conversation.entity.Conversation;
import com.hify.conversation.entity.Message;
import com.hify.conversation.mapper.ConversationMapper;
import com.hify.conversation.mapper.MessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public ConversationStore(ConversationMapper conversationMapper, MessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    /** 事务A：解析/新建会话 + 落 user 消息，返回 conversationId。 */
    @Transactional
    public Long openTurn(Long appId, Long conversationId, Long userId, String userContent) {
        Long cid;
        if (conversationId == null) {
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
        return cid;
    }

    /** 事务B：落 assistant 消息 + touch 会话 update_time。 */
    @Transactional
    public Message appendAssistant(Long conversationId, String content, int promptTokens, int completionTokens) {
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
        return m;
    }

    /** 读：列出某会话消息（按 id 升序）。会话非本人/不存在抛 404。 */
    public List<Message> listMessages(Long conversationId, Long userId) {
        assertOwned(conversationId, userId);
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getId));
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
