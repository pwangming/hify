package com.hify.provider.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 创建模型请求。providerId 来自路径，不在请求体。
 * type 限 chat|embedding；name ≤50；modelKey（API 模型标识）≤100。
 */
public record CreateModelRequest(
        @NotBlank @Pattern(regexp = "chat|embedding", message = "type 仅支持 chat|embedding") String type,
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Size(max = 100) String modelKey,
        @DecimalMin(value = "0", message = "单价不能为负") BigDecimal inputPrice,
        @DecimalMin(value = "0", message = "单价不能为负") BigDecimal outputPrice) {
}
