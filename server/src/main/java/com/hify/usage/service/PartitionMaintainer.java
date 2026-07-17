package com.hify.usage.service;

import com.hify.usage.mapper.LlmCallLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * llm_call_log 月分区维护（database-standards §分区运维：应用内每月建下月分区，不引 pg_partman）。
 * V12 初始建到 2026-12；本任务每月补建，保证写入永远有落点。{@code if not exists} 幂等，向前多建几个月兜底。
 */
@Component
public class PartitionMaintainer {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintainer.class);
    /** 每次向前保证的月数（含当月），冗余覆盖调度漏跑/停机窗口。 */
    private static final int MONTHS_AHEAD = 3;
    /**
     * 分区「月」的时区锚点：既成事实——存量分区边界全部是北京时间零点（V12 由 +08 会话建出）。
     * 边界字面量必须带明确偏移：裸日期按会话时区解释，宿主机(+08)与容器(UTC)会建出相差 8 小时的
     * 边界，导致分区重叠（启动失败）或缝隙（写入失败）。改此常量=改月定义，会与存量边界错位，禁改。
     */
    private static final ZoneId PARTITION_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter BOUND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssxxx");

    private final LlmCallLogMapper mapper;

    public PartitionMaintainer(LlmCallLogMapper mapper) {
        this.mapper = mapper;
    }

    /** 启动即兜底一次（防 V12 末月已过、或长期停机后重启无分区可写）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ensureFuturePartitions(YearMonth.now(PARTITION_ZONE));
    }

    /** 每月 1 日 00:30 补建未来分区。 */
    @Scheduled(cron = "0 30 0 1 * *")
    public void scheduled() {
        ensureFuturePartitions(YearMonth.now(PARTITION_ZONE));
    }

    void ensureFuturePartitions(YearMonth base) {
        for (int i = 0; i <= MONTHS_AHEAD; i++) {
            YearMonth ym = base.plusMonths(i);
            String name = String.format("llm_call_log_%d_%02d", ym.getYear(), ym.getMonthValue());
            mapper.createMonthlyPartition(name, bound(ym), bound(ym.plusMonths(1)));
        }
        log.info("llm_call_log 分区已确保至 {}", base.plusMonths(MONTHS_AHEAD));
    }

    /** 月首日北京时间零点，带明确偏移（如 2026-10-01 00:00:00+08:00），不受运行环境时区影响。 */
    private static String bound(YearMonth ym) {
        return ym.atDay(1).atStartOfDay(PARTITION_ZONE).format(BOUND_FORMAT);
    }
}
