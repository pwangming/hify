package com.hify.knowledge.dto;

import java.time.OffsetDateTime;

/** 知识库视图。id/ownerId 为 Long（infra 全局序列化为 string）。不做 ownerName 联查（前端对比 ownerId）。 */
public record DatasetResponse(
        Long id,
        String name,
        String description,
        Long ownerId,
        OffsetDateTime createTime,
        OffsetDateTime updateTime) {
}
