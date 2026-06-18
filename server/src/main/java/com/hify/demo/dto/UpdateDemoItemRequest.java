package com.hify.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 更新 DemoItem 的入参。PUT 是全量更新（api-standards.md 第 2.2 节），客户端必须传完整对象。
 */
public record UpdateDemoItemRequest(
        @NotBlank(message = "name 不能为空") String name,
        @NotNull(message = "status 不能为空") Integer status) {
}
