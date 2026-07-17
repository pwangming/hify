package com.hify.usage.service;

import com.hify.usage.mapper.LlmCallLogMapper;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 分区边界必须带明确 +08:00 偏移（锚定北京时间零点）。
 * 背景：裸日期字面量按会话时区解释，宿主机(+08)与容器(UTC)建出的边界相差 8 小时，
 * 会造成分区重叠（启动失败）或 8 小时无分区缝隙（写入失败）——2026-07-17 运维补账轮实证。
 */
class PartitionMaintainerTest {

    @Test
    void 补建分区_边界为北京时间零点带明确偏移() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        PartitionMaintainer maintainer = new PartitionMaintainer(mapper);

        maintainer.ensureFuturePartitions(YearMonth.of(2026, 10));

        verify(mapper).createMonthlyPartition(
                "llm_call_log_2026_10", "2026-10-01 00:00:00+08:00", "2026-11-01 00:00:00+08:00");
    }

    @Test
    void 补建分区_覆盖当月起共4个月且跨年正确() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        PartitionMaintainer maintainer = new PartitionMaintainer(mapper);

        maintainer.ensureFuturePartitions(YearMonth.of(2026, 10));

        verify(mapper, times(4)).createMonthlyPartition(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(mapper).createMonthlyPartition(
                "llm_call_log_2027_01", "2027-01-01 00:00:00+08:00", "2027-02-01 00:00:00+08:00");
    }
}
