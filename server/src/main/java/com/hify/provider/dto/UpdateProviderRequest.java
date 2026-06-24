package com.hify.provider.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 全量更新供应商请求。name/protocol/baseUrl 严格全量必填；
 * apiKey 是「只写密钥」对 PUT 全量规则的<b>明确例外</b>（api-standards 第 2 节）：
 * 服务端无法把明文回传给前端整体提交，故留空（null 或空白）= 保留原密文不改，非空 = 重新加密覆盖。
 * 因此 apiKey 不加 @NotBlank。
 */
public record UpdateProviderRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Pattern(regexp = "openai|anthropic", message = "protocol 仅支持 openai|anthropic") String protocol,
        @NotBlank @Pattern(regexp = "^https?://.+", message = "baseUrl 必须以 http:// 或 https:// 开头") String baseUrl,
        String apiKey) {
}
