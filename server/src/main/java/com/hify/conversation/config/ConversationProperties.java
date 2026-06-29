package com.hify.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * conversation 模块配置（hify.conversation.*）。
 * memory.window-rounds：滑动窗口轮数 N，最近 N 轮(2N 条) + 当前消息进 prompt。
 * list.recent-limit：会话侧边栏最近会话条数上限。
 */
@ConfigurationProperties(prefix = "hify.conversation")
public record ConversationProperties(Memory memory, ListProps list) {

    public record Memory(int windowRounds) {
    }

    public record ListProps(int recentLimit) {
    }
}
