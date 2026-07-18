package com.hify.usage.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.api.ModelPrice;
import com.hify.provider.api.ProviderFacade;
import com.hify.usage.dto.DailyUsagePoint;
import com.hify.usage.dto.UsageOverviewResponse;
import com.hify.usage.dto.UsageRankingItem;
import com.hify.usage.mapper.UsageStatQueryMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 看板聚合统计（只读）。查 usage_stat_daily（database-standards §聚合表代替扫流水）；
 * 费用 = token×单价（元/百万）后端 BigDecimal 现算——按当前单价估算，改价历史自动重算（spec §0）。
 * 「已配价」= 两单价均非空；否则该模型费用计 0 且 costIncomplete=true（含已删模型：取价结果里没有）。
 */
@Service
public class UsageStatService {

    private static final int MAX_RANGE_DAYS = 92;
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);
    private static final Map<String, String> DIM_COLUMNS = Map.of(
            "app", "app_id", "user", "user_id", "model", "model_id");

    private final UsageStatQueryMapper mapper;
    private final ProviderFacade providerFacade;

    public UsageStatService(UsageStatQueryMapper mapper, ProviderFacade providerFacade) {
        this.mapper = mapper;
        this.providerFacade = providerFacade;
    }

    public UsageOverviewResponse overview(LocalDate start, LocalDate end) {
        validateRange(start, end);
        List<UsageStatQueryMapper.ModelAgg> rows = mapper.aggregateByModel(start, end);
        Map<Long, ModelPrice> prices = fetchPrices(rows.stream()
                .map(UsageStatQueryMapper.ModelAgg::modelId).collect(Collectors.toSet()));
        long prompt = 0;
        long completion = 0;
        long calls = 0;
        BigDecimal cost = BigDecimal.ZERO;
        boolean incomplete = false;
        for (UsageStatQueryMapper.ModelAgg row : rows) {
            prompt += row.promptTokens();
            completion += row.completionTokens();
            calls += row.callCount();
            ModelPrice price = prices.get(row.modelId());
            if (isPriced(price)) {
                cost = cost.add(costOf(row.promptTokens(), row.completionTokens(), price));
            } else {
                incomplete = true;
            }
        }
        return new UsageOverviewResponse(prompt, completion, prompt + completion, calls,
                scale(cost), incomplete);
    }

    public List<DailyUsagePoint> daily(LocalDate start, LocalDate end) {
        validateRange(start, end);
        List<UsageStatQueryMapper.DailyModelAgg> rows = mapper.aggregateDaily(start, end);
        Map<Long, ModelPrice> prices = fetchPrices(rows.stream()
                .map(UsageStatQueryMapper.DailyModelAgg::modelId).collect(Collectors.toSet()));
        Map<LocalDate, long[]> acc = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal> costs = new LinkedHashMap<>();
        for (UsageStatQueryMapper.DailyModelAgg row : rows) {
            long[] a = acc.computeIfAbsent(row.statDate(), d -> new long[3]);
            a[0] += row.promptTokens();
            a[1] += row.completionTokens();
            a[2] += row.callCount();
            ModelPrice price = prices.get(row.modelId());
            if (isPriced(price)) {
                costs.merge(row.statDate(), costOf(row.promptTokens(), row.completionTokens(), price),
                        BigDecimal::add);
            }
        }
        return acc.entrySet().stream()
                .map(e -> new DailyUsagePoint(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2],
                        scale(costs.getOrDefault(e.getKey(), BigDecimal.ZERO))))
                .toList();
    }

    public List<UsageRankingItem> rankings(String dimension, LocalDate start, LocalDate end, int limit) {
        validateRange(start, end);
        String dimCol = DIM_COLUMNS.get(dimension);
        if (dimCol == null) {
            throw new BizException(CommonError.PARAM_INVALID, "dimension 仅支持 app|user|model");
        }
        List<UsageStatQueryMapper.DimModelAgg> rows = mapper.aggregateByDimension(dimCol, start, end);
        Map<Long, ModelPrice> prices = fetchPrices(rows.stream()
                .map(UsageStatQueryMapper.DimModelAgg::modelId).collect(Collectors.toSet()));
        Map<Long, long[]> acc = new LinkedHashMap<>();
        Map<Long, BigDecimal> costs = new LinkedHashMap<>();
        for (UsageStatQueryMapper.DimModelAgg row : rows) {
            long[] a = acc.computeIfAbsent(row.targetId(), t -> new long[3]);
            a[0] += row.promptTokens();
            a[1] += row.completionTokens();
            a[2] += row.callCount();
            ModelPrice price = prices.get(row.modelId());
            if (isPriced(price)) {
                costs.merge(row.targetId(), costOf(row.promptTokens(), row.completionTokens(), price),
                        BigDecimal::add);
            }
        }
        return acc.entrySet().stream()
                .map(e -> new UsageRankingItem(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[0] + e.getValue()[1], e.getValue()[2],
                        scale(costs.getOrDefault(e.getKey(), BigDecimal.ZERO))))
                .sorted(Comparator.comparingLong(UsageRankingItem::totalTokens).reversed())
                .limit(limit)
                .toList();
    }

    private Map<Long, ModelPrice> fetchPrices(Set<Long> modelIds) {
        return modelIds.isEmpty() ? Map.of() : providerFacade.getModelPrices(modelIds);
    }

    private static boolean isPriced(ModelPrice p) {
        return p != null && p.inputPrice() != null && p.outputPrice() != null;
    }

    private static BigDecimal costOf(long promptTokens, long completionTokens, ModelPrice p) {
        return BigDecimal.valueOf(promptTokens).multiply(p.inputPrice())
                .add(BigDecimal.valueOf(completionTokens).multiply(p.outputPrice()))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    private static String scale(BigDecimal cost) {
        return cost.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static void validateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new BizException(CommonError.PARAM_INVALID, "日期范围不合法");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new BizException(CommonError.PARAM_INVALID, "日期范围不能超过 92 天");
        }
    }
}
