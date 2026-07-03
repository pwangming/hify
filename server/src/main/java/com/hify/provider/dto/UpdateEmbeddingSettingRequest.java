package com.hify.provider.dto;

import jakarta.validation.constraints.NotNull;

/** 设置系统 embedding 模型（PUT 全量，唯一字段）。 */
public record UpdateEmbeddingSettingRequest(@NotNull(message = "modelId 不能为空") Long modelId) {
}
