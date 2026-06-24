package com.hify.provider.dto;

import java.time.OffsetDateTime;

/**
 * 模型出参。id/providerId 为 Long，经 Jackson 全局序列化为字符串；createTime 为 ISO-8601 带时区。
 * modelKey 非敏感，完整返回。本 record 不依赖 entity，投影在 AiModelService 完成。
 */
public record ModelResponse(
        Long id,
        Long providerId,
        String type,
        String name,
        String modelKey,
        String status,
        OffsetDateTime createTime) {
}
