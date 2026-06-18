package com.hify.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建 DemoItem 的入参。校验注解只写在这里（api-standards.md 第 4 节：必填字符串用 {@code @NotBlank}，
 * 必填数字用 {@code @NotNull}）；校验失败由 Controller 的 {@code @Valid} 触发，
 * 全局异常处理器统一转成 10001 + 字段错误数组，service 不重复校验。
 */
public record CreateDemoItemRequest(
        @NotBlank(message = "name 不能为空") String name,
        @NotNull(message = "status 不能为空") Integer status) {
}
