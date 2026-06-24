package com.hify.provider.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 更新模型请求。仅改 name + modelKey；type/providerId 创建后不可改，不在请求体。
 */
public record UpdateModelRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Size(max = 100) String modelKey) {
}
