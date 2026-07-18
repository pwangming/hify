package com.hify.usage.service;

import com.hify.common.exception.BizException;
import com.hify.provider.api.ModelPrice;
import com.hify.provider.api.ProviderFacade;
import com.hify.usage.dto.UsageOverviewResponse;
import com.hify.usage.mapper.UsageStatQueryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageStatServiceTest {

    UsageStatQueryMapper mapper = mock(UsageStatQueryMapper.class);
    ProviderFacade providerFacade = mock(ProviderFacade.class);
    UsageStatService service;

    @BeforeEach
    void setUp() {
        service = new UsageStatService(mapper, providerFacade);
    }

    @Test
    void overview_已配价模型算费用_未配价计0并标注不完整() {
        when(mapper.aggregateByModel(any(), any())).thenReturn(List.of(
                new UsageStatQueryMapper.ModelAgg(5L, 1_000_000L, 500_000L, 3L),
                new UsageStatQueryMapper.ModelAgg(9L, 2_000_000L, 1_000_000L, 2L)));
        when(providerFacade.getModelPrices(any())).thenReturn(Map.of(
                5L, new ModelPrice(new BigDecimal("2"), new BigDecimal("6"))));
        UsageOverviewResponse r = service.overview(
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-17"));
        assertThat(r.promptTokens()).isEqualTo(3_000_000L);
        assertThat(r.completionTokens()).isEqualTo(1_500_000L);
        assertThat(r.callCount()).isEqualTo(5L);
        assertThat(r.estimatedCost()).isEqualTo("5.0000");
        assertThat(r.costIncomplete()).isTrue();
    }

    @Test
    void 只配一半单价的模型视为未配价() {
        when(mapper.aggregateByModel(any(), any())).thenReturn(List.of(
                new UsageStatQueryMapper.ModelAgg(5L, 1_000_000L, 0L, 1L)));
        when(providerFacade.getModelPrices(any())).thenReturn(Map.of(
                5L, new ModelPrice(new BigDecimal("2"), null)));
        UsageOverviewResponse r = service.overview(
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-02"));
        assertThat(r.estimatedCost()).isEqualTo("0.0000");
        assertThat(r.costIncomplete()).isTrue();
    }

    @Test
    void 日期窗校验_起晚于止或超92天_抛10001() {
        assertThatThrownBy(() -> service.overview(
                LocalDate.parse("2026-07-02"), LocalDate.parse("2026-07-01")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.overview(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-07-01")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rankings_dimension非法抛10001_合法按totalTokens降序截断limit() {
        when(mapper.aggregateByDimension(any(), any(), any())).thenReturn(List.of(
                new UsageStatQueryMapper.DimModelAgg(88L, 5L, 100L, 50L, 1L),
                new UsageStatQueryMapper.DimModelAgg(99L, 5L, 900L, 100L, 2L)));
        when(providerFacade.getModelPrices(any())).thenReturn(Map.of());
        var list = service.rankings(
                "app", LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-02"), 1);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).targetId()).isEqualTo(99L);
        assertThatThrownBy(() -> service.rankings(
                "bogus", LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-02"), 10))
                .isInstanceOf(BizException.class);
    }
}
