package com.hify.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建知识库入参。name 必填 ≤50；description 选填 ≤200（与 V13 check 同刻度）。 */
public record CreateDatasetRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 200) String description) {
}
