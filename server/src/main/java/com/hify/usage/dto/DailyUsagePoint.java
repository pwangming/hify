package com.hify.usage.dto;

import java.time.LocalDate;

public record DailyUsagePoint(LocalDate date, long promptTokens, long completionTokens,
                              long callCount, String estimatedCost) {
}
