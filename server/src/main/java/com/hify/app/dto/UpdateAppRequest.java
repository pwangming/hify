package com.hify.app.dto;

import com.hify.app.api.dto.AppConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 全量更新应用请求（PUT 语义）。type 不可改，故无 type 字段。 */
public record UpdateAppRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 200) String description,
        Long modelId,
        AppConfig config) {
}
