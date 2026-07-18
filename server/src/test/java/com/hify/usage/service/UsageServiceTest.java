package com.hify.usage.service;

import com.hify.common.event.TokenUsedEvent;
import com.hify.common.exception.BizException;
import com.hify.usage.config.UsageProperties;
import com.hify.usage.constant.UsageError;
import com.hify.usage.mapper.LlmCallLogMapper;
import com.hify.usage.mapper.UsageStatDailyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageServiceTest {

    private static final long LIMIT = 1000L;

    private LlmCallLogMapper llmCallLogMapper;
    private UsageStatDailyMapper statDailyMapper;
    private UsageService service;

    @BeforeEach
    void setUp() {
        llmCallLogMapper = mock(LlmCallLogMapper.class);
        statDailyMapper = mock(UsageStatDailyMapper.class);
        service = new UsageService(
                llmCallLogMapper, statDailyMapper, new UsageProperties(LIMIT));
    }

    @Test
    void checkQuota_今日已用未达上限_放行() {
        when(statDailyMapper.sumTodayByUser(eq(7L), any(LocalDate.class))).thenReturn(LIMIT - 1);
        assertDoesNotThrow(() -> service.checkQuota(7L, 88L));
    }

    @Test
    void checkQuota_今日已用刚好等于上限_拦截并抛14001() {
        when(statDailyMapper.sumTodayByUser(eq(7L), any(LocalDate.class))).thenReturn(LIMIT);
        BizException ex = assertThrows(BizException.class, () -> service.checkQuota(7L, 88L));
        assertEquals(UsageError.QUOTA_EXCEEDED, ex.errorCode());
    }

    @Test
    void checkQuota_今日已用超过上限_拦截() {
        when(statDailyMapper.sumTodayByUser(eq(7L), any(LocalDate.class))).thenReturn(LIMIT + 1);
        assertThrows(BizException.class, () -> service.checkQuota(7L, 88L));
    }

    @Test
    void recordUsage_成功事件_插流水且聚合累加() {
        service.recordUsage(TokenUsedEvent.success(
                7L, 88L, 5L, 300, 180, TokenUsedEvent.SOURCE_CONVERSATION, 1234L));

        verify(llmCallLogMapper, times(1)).insertLog(
                7L, 88L, 5L, 300L, 180L, TokenUsedEvent.SOURCE_CONVERSATION, 1234L, "success", null);
        verify(statDailyMapper).upsertAccumulate(
                eq(7L), eq(88L), eq(5L), any(LocalDate.class), eq(300L), eq(180L));
    }

    @Test
    void recordUsage_失败事件_只插流水_不动聚合不进配额() {
        service.recordUsage(TokenUsedEvent.failure(
                7L, 88L, 5L, TokenUsedEvent.SOURCE_WORKFLOW, 890L, new IllegalStateException("x")));

        verify(llmCallLogMapper, times(1)).insertLog(
                7L, 88L, 5L, 0L, 0L, TokenUsedEvent.SOURCE_WORKFLOW, 890L, "failed", "IllegalStateException");
        verify(statDailyMapper, never()).upsertAccumulate(
                any(), any(), any(), any(LocalDate.class), anyLong(), anyLong());
    }
}
