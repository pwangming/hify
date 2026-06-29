package com.hify.conversation.service;

import com.hify.conversation.constant.MessageRole;
import com.hify.conversation.entity.Message;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ChatInvokerTest {

    private final ChatInvoker invoker = new ChatInvoker();

    private Message msg(String role, String content) {
        Message m = new Message();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    void toMessages_有systemPrompt_首位System_其后按窗口顺序映射角色() {
        List<org.springframework.ai.chat.messages.Message> out = invoker.toMessages("你是客服", List.of(
                msg(MessageRole.USER.value(), "a"),
                msg(MessageRole.ASSISTANT.value(), "b"),
                msg(MessageRole.USER.value(), "c")));

        assertEquals(4, out.size());
        assertInstanceOf(SystemMessage.class, out.get(0));
        assertInstanceOf(UserMessage.class, out.get(1));
        assertInstanceOf(AssistantMessage.class, out.get(2));
        assertInstanceOf(UserMessage.class, out.get(3));
        assertEquals("c", out.get(3).getText()); // 末位即当前消息
    }

    @Test
    void toMessages_systemPrompt为空_不加System段() {
        List<org.springframework.ai.chat.messages.Message> out = invoker.toMessages(null, List.of(
                msg(MessageRole.USER.value(), "仅当前")));

        assertEquals(1, out.size());
        assertInstanceOf(UserMessage.class, out.get(0));
        assertEquals("仅当前", out.get(0).getText());
    }
}
