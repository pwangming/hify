package com.hify.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 重命名会话请求体：仅标题。@NotBlank 空/纯空白 → 10001；@Size 对齐自动标题上限 100。 */
public record RenameConversationRequest(@NotBlank @Size(max = 100) String title) {
}
