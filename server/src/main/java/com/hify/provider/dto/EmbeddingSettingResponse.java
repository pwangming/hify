package com.hify.provider.dto;

/** 系统 embedding 模型设置回显。未配置时两字段均为 null。 */
public record EmbeddingSettingResponse(Long modelId, String modelName) {
}
