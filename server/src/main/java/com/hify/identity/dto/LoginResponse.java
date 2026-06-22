package com.hify.identity.dto;

/**
 * 登录成功响应。仅本模块 controller 用，禁止被其他模块 import。
 *
 * <p>{@code userId} 用于前端的 owner 判断（canEdit = isAdmin || ownerId === currentUserId）；
 * 这是前端内部 API（/api/v1/**），可暴露站内自增 id（"不暴露自增主键"针对的是对外 /v1/apps/**）。
 * Long 序列化为 JSON 字符串由 infra 的 Jackson 全局配置处理（防 JS 精度丢失）。
 */
public record LoginResponse(String token, Long userId, String username, String role) {
}
