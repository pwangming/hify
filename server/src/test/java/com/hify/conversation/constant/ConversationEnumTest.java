package com.hify.conversation.constant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversationEnumTest {

    @Test
    void 角色值与DB_check一致() {
        assertEquals("user", MessageRole.USER.value());
        assertEquals("assistant", MessageRole.ASSISTANT.value());
    }

    @Test
    void 错误码_APP_NOT_RUNNABLE_为17001_400() {
        assertEquals(17001, ConversationError.APP_NOT_RUNNABLE.code());
        assertEquals(HttpStatus.BAD_REQUEST, ConversationError.APP_NOT_RUNNABLE.status());
    }
}
