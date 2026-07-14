package com.hify.app.dto;

import com.hify.app.api.dto.AppConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 全量更新应用请求（PUT 语义）。type 不可改，故无 type 字段。datasetIds 可空=清空绑定，上限 10；toolIds 可空=不启用工具，上限 20。 */
public record UpdateAppRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 200) String description,
        Long modelId,
        AppConfig config,
        @Size(max = 10) List<Long> datasetIds,
        @Size(max = 20) List<Long> toolIds) {
}
