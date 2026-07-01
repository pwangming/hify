package com.hify.usage.service;

import com.hify.common.event.TokenUsedEvent;
import com.hify.common.exception.BizException;
import com.hify.usage.config.UsageProperties;
import com.hify.usage.constant.UsageError;
import com.hify.usage.mapper.DailyUsageMapper;
import com.hify.usage.mapper.LlmCallLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageServiceTest {

    private static final long LIMIT = 1000L;

    private DailyUsageMapper dailyUsageMapper;
    private LlmCallLogMapper llmCallLogMapper;
    private UsageService service;

    @BeforeEach
    void setUp() {
        dailyUsageMapper = mock(DailyUsageMapper.class);
        llmCallLogMapper = mock(LlmCallLogMapper.class);
        service = new UsageService(dailyUsageMapper, llmCallLogMapper, new UsageProperties(LIMIT));
    }

    @Test
    void checkQuota_今日已用未达上限_放行() {
        when(dailyUsageMapper.sumTodayByUser(eq(7L), any(LocalDate.class))).thenReturn(LIMIT - 1);
        assertDoesNotThrow(() -> service.checkQuota(7L, 88L));
    }

    @Test
    void checkQuota_今日已用刚好等于上限_拦截并抛14001() {
        when(dailyUsageMapper.sumTodayByUser(eq(7L), any(LocalDate.class))).thenReturn(LIMIT);
        BizException ex = assertThrows(BizException.class, () -> service.checkQuota(7L, 88L));
        assertEquals(UsageError.QUOTA_EXCEEDED, ex.errorCode());
    }

    @Test
    void checkQuota_今日已用超过上限_拦截() {
        when(dailyUsageMapper.sumTodayByUser(eq(7L), any(LocalDate.class))).thenReturn(LIMIT + 1);
        assertThrows(BizException.class, () -> service.checkQuota(7L, 88L));
    }

    @Test
    void recordUsage_落一行流水并按总token累加聚合() {
        service.recordUsage(new TokenUsedEvent(7L, 88L, 5L, 300, 180));

        verify(llmCallLogMapper, times(1)).insertLog(7L, 88L, 5L, 300L, 180L);
        verify(dailyUsageMapper, times(1)).upsertAccumulate(eq(7L), eq(88L), any(LocalDate.class), eq(480L));
    }
}
