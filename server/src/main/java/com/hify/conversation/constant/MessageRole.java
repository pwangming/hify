package com.hify.conversation.constant;

/** 消息角色，value() 与 DB check（'user'/'assistant'）一致。 */
public enum MessageRole {

    USER("user"),
    ASSISTANT("assistant");

    private final String value;

    MessageRole(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
