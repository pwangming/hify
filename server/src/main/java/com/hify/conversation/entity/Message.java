package com.hify.conversation.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;
import com.hify.conversation.config.MessageSourcesTypeHandler;
import com.hify.conversation.dto.MessageSource;
import com.hify.conversation.dto.MessageToolCall;

import java.util.List;

/**
 * 消息表 {@code message} 映射实体。role 存 'user'/'assistant'（见 MessageRole）。
 * tool_calls(jsonb) 记录 Agent 工具调用轨迹（非 Agent 消息 DB 默认 '[]'）。
 * sources(jsonb) 经 {@link MessageSourcesTypeHandler} 读写；autoResultMap=true 才让处理器在查询映射生效。
 */
@TableName(value = "message", autoResultMap = true)
public class Message extends BaseEntity {

    private Long conversationId;
    private String role;
    private String content;
    private Integer promptTokens;
    private Integer completionTokens;

    @TableField(typeHandler = MessageSourcesTypeHandler.class)
    private List<MessageSource> sources;

    @TableField(typeHandler = com.hify.conversation.config.MessageToolCallsTypeHandler.class)
    private List<MessageToolCall> toolCalls;

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

    public List<MessageSource> getSources() { return sources; }
    public void setSources(List<MessageSource> sources) { this.sources = sources; }

    public List<MessageToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<MessageToolCall> toolCalls) { this.toolCalls = toolCalls; }
}
