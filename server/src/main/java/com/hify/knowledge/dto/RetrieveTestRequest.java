package com.hify.knowledge.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 命中测试请求（api-standards 预写范式 POST /datasets/{id}/retrieve）。topK/scoreThreshold 可空=用全局配置，给了本次生效（调参用）。 */
public record RetrieveTestRequest(
        @NotBlank @Size(max = 1000) String query,
        @Min(1) @Max(20) Integer topK,
        @DecimalMin("0.0") @DecimalMax("1.0") Double scoreThreshold) {
}
