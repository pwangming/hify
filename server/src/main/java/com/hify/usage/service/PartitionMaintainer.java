package com.hify.usage.service;

import com.hify.usage.mapper.LlmCallLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * llm_call_log 月分区维护（database-standards §分区运维：应用内每月建下月分区，不引 pg_partman）。
 * V12 初始建到 2026-12；本任务每月补建，保证写入永远有落点。{@code if not exists} 幂等，向前多建几个月兜底。
 */
@Component
public class PartitionMaintainer {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintainer.class);
    /** 每次向前保证的月数（含当月），冗余覆盖调度漏跑/停机窗口。 */
    private static final int MONTHS_AHEAD = 3;

    private final LlmCallLogMapper mapper;

    public PartitionMaintainer(LlmCallLogMapper mapper) {
        this.mapper = mapper;
    }

    /** 启动即兜底一次（防 V12 末月已过、或长期停机后重启无分区可写）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ensureFuturePartitions();
    }

    /** 每月 1 日 00:30 补建未来分区。 */
    @Scheduled(cron = "0 30 0 1 * *")
    public void scheduled() {
        ensureFuturePartitions();
    }

    private void ensureFuturePartitions() {
        YearMonth base = YearMonth.now();
        for (int i = 0; i <= MONTHS_AHEAD; i++) {
            YearMonth ym = base.plusMonths(i);
            String name = String.format("llm_call_log_%d_%02d", ym.getYear(), ym.getMonthValue());
            mapper.createMonthlyPartition(name, ym.atDay(1).toString(), ym.plusMonths(1).atDay(1).toString());
        }
        log.info("llm_call_log 分区已确保至 {}", base.plusMonths(MONTHS_AHEAD));
    }
}
