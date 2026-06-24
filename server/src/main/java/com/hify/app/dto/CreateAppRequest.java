package com.hify.app.dto;

import com.hify.app.api.dto.AppConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建应用请求。type 本轮仅 'chat' 放行（service 判，workflow→16001）。modelId 可空、本轮存而不校。
 * config 可空（service 兜底为空配置）。校验注解只写在本层。
 */
public record CreateAppRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 200) String description,
        @NotBlank String type,
        Long modelId,
        AppConfig config) {
}
