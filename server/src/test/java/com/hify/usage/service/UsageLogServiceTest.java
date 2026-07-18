package com.hify.usage.service;

import com.hify.common.page.CursorResult;
import com.hify.usage.dto.CallLogItem;
import com.hify.usage.mapper.LlmCallLogMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageLogServiceTest {
    @Test
    void list_观测列透传_历史行null保留() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        OffsetDateTime t = OffsetDateTime.parse("2026-07-18T10:00:00+08:00");
        when(mapper.selectPage(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        new LlmCallLogMapper.CallLogRow(1L, 7L, 88L, 5L, 300, 180,
                                "conversation", 1500, "success", null, t),
                        new LlmCallLogMapper.CallLogRow(2L, 7L, 88L, 5L, 0, 0,
                                "workflow", null, "failed", "12002", t.minusMinutes(1))));
        CursorResult<CallLogItem> page = new UsageLogService(mapper)
                .list(t.minusDays(1), t.plusDays(1), null, null, null, null, null, 20);
        assertThat(page.list().get(0).durationMs()).isEqualTo(1500);
        assertThat(page.list().get(0).status()).isEqualTo("success");
        assertThat(page.list().get(1).durationMs()).isNull();
        assertThat(page.list().get(1).errorCode()).isEqualTo("12002");
    }
}
