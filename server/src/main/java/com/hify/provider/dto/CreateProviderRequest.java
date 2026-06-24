package com.hify.provider.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建供应商请求。校验只在此 DTO（api-standards 第 4 节）：名称 ≤50（对齐 DB check）、
 * protocol 限 openai|anthropic、baseUrl 须 http/https 前缀、apiKey 创建必填。
 */
public record CreateProviderRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Pattern(regexp = "openai|anthropic", message = "protocol 仅支持 openai|anthropic") String protocol,
        @NotBlank @Pattern(regexp = "^https?://.+", message = "baseUrl 必须以 http:// 或 https:// 开头") String baseUrl,
        @NotBlank String apiKey) {
}
