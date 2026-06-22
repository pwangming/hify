package com.hify.identity.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录入参。校验注解只写在这里；校验失败由 Controller 的 @Valid 触发，
 * 全局异常处理器统一转 10001 + 字段错误数组。仅本模块用，禁止被其他模块 import。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password) {
}
