package com.hify.tool.dto;

import jakarta.validation.constraints.NotBlank;

/** 预览：仅粘贴 OpenAPI 文档，服务端只解析不落库。 */
public record PreviewToolRequest(@NotBlank String specText) {}
