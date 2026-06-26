package com.hify.conversation.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 消息表 {@code message} 映射实体。role 存 'user'/'assistant'（见 MessageRole）。
 * tool_calls(jsonb) 本轮恒空、不映射字段（DB 默认 '[]'），留待 Agent 轨迹。
 */
@TableName("message")
public class Message extends BaseEntity {

    private Long conversationId;
    private String role;
    private String content;
    private Integer promptTokens;
    private Integer completionTokens;

    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
}
