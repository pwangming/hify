package com.hify.tool.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 全量更新自定义工具：名称、描述、OpenAPI 文档、鉴权头一起替换。 */
public record UpdateToolRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 500) String description,
        @NotBlank String specText,
        @Valid List<AuthHeaderInput> authHeaders) {}
