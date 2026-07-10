package com.hify.workflow.service;

import com.hify.workflow.mapper.WorkflowNodeRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * workflow_node_run 月分区维护（db-standards §分区运维）。V21 初始建到 2026-12，本任务每月补建。
 * 模式照抄 usage.PartitionMaintainer（各模块自管分区，不跨模块共用类）。
 */
@Component
public class WorkflowPartitionMaintainer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPartitionMaintainer.class);
    /** 每次向前保证的月数（含当月），冗余覆盖调度漏跑/停机窗口。 */
    private static final int MONTHS_AHEAD = 3;

    private final WorkflowNodeRunMapper mapper;

    public WorkflowPartitionMaintainer(WorkflowNodeRunMapper mapper) {
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ensureFuturePartitions();
    }

    /** 每月 1 日 00:35（与 usage 的 00:30 错峰）补建未来分区。 */
    @Scheduled(cron = "0 35 0 1 * *")
    public void scheduled() {
        ensureFuturePartitions();
    }

    private void ensureFuturePartitions() {
        YearMonth base = YearMonth.now();
        for (int i = 0; i <= MONTHS_AHEAD; i++) {
            YearMonth ym = base.plusMonths(i);
            String name = String.format("workflow_node_run_%d_%02d", ym.getYear(), ym.getMonthValue());
            mapper.createMonthlyPartition(name, ym.atDay(1).toString(), ym.plusMonths(1).atDay(1).toString());
        }
        log.info("workflow_node_run 分区已确保至 {}", base.plusMonths(MONTHS_AHEAD));
    }
}
