package com.hify.app.dto;

import com.hify.app.api.dto.AppConfig;

import java.time.OffsetDateTime;

/** 应用视图。id/modelId/ownerId 为 Long（infra 全局序列化为 string）。 */
public record AppResponse(
        Long id,
        String name,
        String description,
        String type,
        Long modelId,
        AppConfig config,
        Long ownerId,
        String status,
        OffsetDateTime createTime,
        OffsetDateTime updateTime) {
}
