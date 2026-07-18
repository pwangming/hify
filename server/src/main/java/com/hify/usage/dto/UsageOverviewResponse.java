package com.hify.usage.dto;

public record UsageOverviewResponse(long promptTokens, long completionTokens, long totalTokens,
                                    long callCount, String estimatedCost, boolean costIncomplete) {
}
