package com.hify.usage.dto;

public record UsageRankingItem(Long targetId, long promptTokens, long completionTokens, long totalTokens,
                               long callCount, String estimatedCost) {
}
