package com.hify.conversation.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.conversation.constant.MessageRole;
import com.hify.conversation.entity.Conversation;
import com.hify.conversation.entity.Message;
import com.hify.conversation.mapper.ConversationMapper;
import com.hify.conversation.mapper.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationStoreTest {

    private ConversationMapper conversationMapper;
    private MessageMapper messageMapper;
    private ConversationStore store;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        messageMapper = mock(MessageMapper.class);
        store = new ConversationStore(conversationMapper, messageMapper);
    }

    // 模拟 MyBatis-Plus insert 回填自增 id
    private void stubInsertAssignsId(Long id) {
        when(conversationMapper.insert(any(Conversation.class))).thenAnswer((InvocationOnMock inv) -> {
            inv.getArgument(0, Conversation.class).setId(id);
            return 1;
        });
    }

    @Test
    void openTurn_新会话_建会话取title并落user消息_返回新id() {
        stubInsertAssignsId(100L);
        ArgumentCaptor<Conversation> cc = ArgumentCaptor.forClass(Conversation.class);
        ArgumentCaptor<Message> mc = ArgumentCaptor.forClass(Message.class);

        Long cid = store.openTurn(7L, null, 42L, "  你好，介绍一下你自己  ");

        assertEquals(100L, cid);
        verify(conversationMapper).insert((Conversation) cc.capture());
        assertEquals(7L, cc.getValue().getAppId());
        assertEquals(42L, cc.getValue().getUserId());
        assertEquals("你好，介绍一下你自己", cc.getValue().getTitle()); // 已 strip
        verify(messageMapper).insert((Message) mc.capture());
        assertEquals(100L, mc.getValue().getConversationId());
        assertEquals(MessageRole.USER.value(), mc.getValue().getRole());
        assertEquals("你好，介绍一下你自己", mc.getValue().getContent());
    }

    @Test
    void openTurn_续聊本人会话_不建会话_只落user消息() {
        Conversation existing = new Conversation();
        existing.setId(100L);
        existing.setUserId(42L);
        when(conversationMapper.selectById(eq(100L))).thenReturn(existing);

        Long cid = store.openTurn(7L, 100L, 42L, "继续");

        assertEquals(100L, cid);
        verify(conversationMapper, never()).insert(any(Conversation.class));
        verify(messageMapper).insert(any(Message.class));
    }

    @Test
    void openTurn_续聊他人会话_404() {
        Conversation other = new Conversation();
        other.setId(100L);
        other.setUserId(999L);
        when(conversationMapper.selectById(eq(100L))).thenReturn(other);

        BizException ex = assertThrows(BizException.class,
                () -> store.openTurn(7L, 100L, 42L, "继续"));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    void appendAssistant_落assistant消息含token_并touch会话() {
        ArgumentCaptor<Message> mc = ArgumentCaptor.forClass(Message.class);
        Message saved = store.appendAssistant(100L, "你好，我是助手", 12, 8);

        verify(messageMapper).insert((Message) mc.capture());
        assertEquals(MessageRole.ASSISTANT.value(), mc.getValue().getRole());
        assertEquals("你好，我是助手", mc.getValue().getContent());
        assertEquals(12, mc.getValue().getPromptTokens());
        assertEquals(8, mc.getValue().getCompletionTokens());
        verify(conversationMapper).updateById(any(Conversation.class)); // touch update_time
        assertEquals(mc.getValue(), saved);
    }

    @Test
    void listMessages_他人会话_404() {
        Conversation other = new Conversation();
        other.setUserId(999L);
        when(conversationMapper.selectById(eq(100L))).thenReturn(other);
        BizException ex = assertThrows(BizException.class, () -> store.listMessages(100L, 42L));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }
}
