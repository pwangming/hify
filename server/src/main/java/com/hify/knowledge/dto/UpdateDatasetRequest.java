package com.hify.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 全量更新入参（PUT 语义：description 不传即置空，api-standards §2.2）。 */
public record UpdateDatasetRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 200) String description) {
}
