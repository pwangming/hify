package com.hify.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * conversation 模块配置（hify.conversation.*）。
 * memory.window-rounds：滑动窗口轮数 N。list.recent-limit：侧边栏最近会话上限。
 * source-preview-length：引用来源卡片预览截断字数（不存全文，database/前端展示用）。
 */
@ConfigurationProperties(prefix = "hify.conversation")
public record ConversationProperties(Memory memory, ListProps list, int sourcePreviewLength) {

    public record Memory(int windowRounds) {
    }

    public record ListProps(int recentLimit) {
    }
}
