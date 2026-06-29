package com.hify.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * conversation 模块配置（hify.conversation.*）。
 * memory.window-rounds：滑动窗口轮数 N，最近 N 轮(2N 条) + 当前消息进 prompt。
 */
@ConfigurationProperties(prefix = "hify.conversation")
public record ConversationProperties(Memory memory) {

    public record Memory(int windowRounds) {
    }
}
