package com.hify.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 重置密码请求。密码规则同创建。 */
public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 72) String password) {
}
