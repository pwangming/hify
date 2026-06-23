package com.hify.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建用户请求。校验只在此 DTO（api-standards 第 4 节）：用户名 ≤50（对齐 DB check）、
 * 密码 8~72（72 为 BCrypt 字节上限，超出会被静默截断，挡在入口）、角色限 admin|member。
 */
public record CreateUserRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Pattern(regexp = "admin|member") String role) {
}
