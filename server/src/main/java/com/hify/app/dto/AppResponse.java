package com.hify.app.dto;

import com.hify.app.api.dto.AppConfig;

import java.time.OffsetDateTime;

/**
 * 应用视图。id/modelId/ownerId 为 Long（infra 全局序列化为 string）。
 * modelName 为展示用模型名（经 ProviderFacade 解析，不管启停都给名字；未选模型/解析不到为 null）。
 */
public record AppResponse(
        Long id,
        String name,
        String description,
        String type,
        Long modelId,
        String modelName,
        AppConfig config,
        Long ownerId,
        String status,
        OffsetDateTime createTime,
        OffsetDateTime updateTime) {
}
