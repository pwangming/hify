package com.hify.workflow.service;

import com.hify.workflow.mapper.WorkflowNodeRunMapper;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 分区边界必须带明确 +08:00 偏移（锚定北京时间零点），镜像 usage.PartitionMaintainerTest。
 * 背景见该测试类注释（会话时区漂移致重叠/缝隙，2026-07-17 实证）。
 */
class WorkflowPartitionMaintainerTest {

    @Test
    void 补建分区_边界为北京时间零点带明确偏移() {
        WorkflowNodeRunMapper mapper = mock(WorkflowNodeRunMapper.class);
        WorkflowPartitionMaintainer maintainer = new WorkflowPartitionMaintainer(mapper);

        maintainer.ensureFuturePartitions(YearMonth.of(2026, 10));

        verify(mapper).createMonthlyPartition(
                "workflow_node_run_2026_10", "2026-10-01 00:00:00+08:00", "2026-11-01 00:00:00+08:00");
    }

    @Test
    void 补建分区_覆盖当月起共4个月且跨年正确() {
        WorkflowNodeRunMapper mapper = mock(WorkflowNodeRunMapper.class);
        WorkflowPartitionMaintainer maintainer = new WorkflowPartitionMaintainer(mapper);

        maintainer.ensureFuturePartitions(YearMonth.of(2026, 10));

        verify(mapper, times(4)).createMonthlyPartition(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(mapper).createMonthlyPartition(
                "workflow_node_run_2027_01", "2027-01-01 00:00:00+08:00", "2027-02-01 00:00:00+08:00");
    }
}
