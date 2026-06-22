package com.hify.identity.dto;

/**
 * GET /api/v1/identity/me 的响应：当前登录用户身份。仅本模块用，禁止被其他模块 import。
 *
 * <p>{@code id} 来自 JWT 里的 userId（Long），经 infra JacksonConfig 全局序列化为 JSON 字符串
 * （防 JS 2^53 精度丢失），对齐前端 UserInfo{ id: string, username, role }。
 */
public record MeResponse(Long id, String username, String role) {
}
