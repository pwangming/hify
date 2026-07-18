package com.hify.usage.controller;

import com.hify.common.Result;
import com.hify.usage.dto.DailyUsagePoint;
import com.hify.usage.dto.UsageOverviewResponse;
import com.hify.usage.dto.UsageRankingItem;
import com.hify.usage.service.UsageStatService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * admin 用量看板接口（仅 Admin，/api/v1/admin/** 由 SecurityConfig hasRole 拦截）。
 * 全 GET 只读；参数校验（日期窗/维度）在 service 统一抛 10001。协议层不写业务。
 */
@RestController
@RequestMapping("/api/v1/admin/usage")
public class AdminUsageController {

    private final UsageStatService usageStatService;

    public AdminUsageController(UsageStatService usageStatService) {
        this.usageStatService = usageStatService;
    }

    @GetMapping("/stats/overview")
    public Result<UsageOverviewResponse> overview(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.ok(usageStatService.overview(startDate, endDate));
    }

    @GetMapping("/stats/daily")
    public Result<List<DailyUsagePoint>> daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.ok(usageStatService.daily(startDate, endDate));
    }

    @GetMapping("/stats/rankings")
    public Result<List<UsageRankingItem>> rankings(
            @RequestParam String dimension,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "10") int limit) {
        return Result.ok(usageStatService.rankings(dimension, startDate, endDate, Math.min(limit, 50)));
    }
}
