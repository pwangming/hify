package com.hify.usage.dto;

import java.time.OffsetDateTime;

public record CallLogItem(Long id, Long userId, Long appId, Long modelId, long promptTokens,
                          long completionTokens, String source, OffsetDateTime createTime) {
}
