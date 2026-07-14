package com.hify.tool.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 注册自定义工具：粘贴 OpenAPI 文档 + 可选鉴权头。 */
public record CreateToolRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 500) String description,
        @NotBlank String specText,
        @Valid List<AuthHeaderInput> authHeaders) {}
