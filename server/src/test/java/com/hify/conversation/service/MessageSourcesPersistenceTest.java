package com.hify.conversation.service;

import com.hify.conversation.dto.MessageSource;
import com.hify.conversation.entity.Conversation;
import com.hify.conversation.entity.Message;
import com.hify.conversation.mapper.ConversationMapper;
import com.hify.conversation.mapper.MessageMapper;
import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageSourcesPersistenceTest extends PgIntegrationTest {

    @Autowired MessageMapper messageMapper;
    @Autowired ConversationMapper conversationMapper;

    private Long newConversation() {
        Conversation c = new Conversation();
        c.setAppId(1L);
        c.setUserId(42L);
        conversationMapper.insert(c);
        return c.getId();
    }

    @Test
    void sources_roundtrip_through_jsonb() {
        Long cid = newConversation();
        Message m = new Message();
        m.setConversationId(cid);
        m.setRole("assistant");
        m.setContent("答案正文");
        m.setPromptTokens(1);
        m.setCompletionTokens(2);
        m.setSources(List.of(new MessageSource(10L, 20L, "产品手册.pdf", 0.82, "安装前需确认电压范围")));
        messageMapper.insert(m);

        Message read = messageMapper.selectById(m.getId());
        assertEquals(1, read.getSources().size());
        MessageSource s = read.getSources().get(0);
        assertEquals(10L, s.chunkId());
        assertEquals("产品手册.pdf", s.documentName());
        assertEquals(0.82, s.score());
        assertEquals("安装前需确认电压范围", s.preview());
    }

    @Test
    void legacy_message_without_sources_reads_empty_list() {
        Long cid = newConversation();
        Message m = new Message();
        m.setConversationId(cid);
        m.setRole("user");
        m.setContent("历史消息");
        messageMapper.insert(m);

        Message read = messageMapper.selectById(m.getId());
        assertTrue(read.getSources().isEmpty());
    }
}
