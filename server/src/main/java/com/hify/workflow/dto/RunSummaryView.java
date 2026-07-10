package com.hify.workflow.dto;

import java.time.OffsetDateTime;

/** 运行历史列表行（摘要，不带 inputs/outputs 大列）。 */
public record RunSummaryView(Long id, String status, String errorMessage,
                             Long elapsedMs, OffsetDateTime createTime) {
}
