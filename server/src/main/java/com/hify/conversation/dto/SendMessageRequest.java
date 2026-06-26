package com.hify.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 发消息请求。appId 必填；conversationId 可空（空=新建会话）；content 必填非空白（全局 trim）。
 * 校验注解只写在本层。
 */
public record SendMessageRequest(
        @NotNull Long appId,
        Long conversationId,
        @NotBlank String content) {
}
