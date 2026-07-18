package com.hify.usage.service;

import com.hify.common.event.TokenUsedEvent;
import com.hify.common.exception.BizException;
import com.hify.usage.config.UsageProperties;
import com.hify.usage.constant.UsageError;
import com.hify.usage.mapper.DailyUsageMapper;
import com.hify.usage.mapper.LlmCallLogMapper;
import com.hify.usage.mapper.UsageStatDailyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * usage 模块核心：入口配额检查（读）+ 用量落库聚合（写）。
 *
 * <p>{@link #checkQuota} 只查 daily_usage 聚合表（不扫流水），由入口同步调用；
 * {@link #recordUsage} 由事件监听器异步调用，事件与主链路解耦（code-organization §4）。
 */
@Service
public class UsageService {

    private final DailyUsageMapper dailyUsageMapper;
    private final LlmCallLogMapper llmCallLogMapper;
    private final UsageStatDailyMapper statDailyMapper;
    private final UsageProperties props;

    public UsageService(DailyUsageMapper dailyUsageMapper, LlmCallLogMapper llmCallLogMapper,
                        UsageStatDailyMapper statDailyMapper, UsageProperties props) {
        this.dailyUsageMapper = dailyUsageMapper;
        this.llmCallLogMapper = llmCallLogMapper;
        this.statDailyMapper = statDailyMapper;
        this.props = props;
    }

    /**
     * 按用户/天封顶：今日已用 Token 合计 >= 上限即拦，抛 14001/429。
     * appId 本轮未参与判定（按用户封顶），保留供 per-app 配额扩展。
     */
    public void checkQuota(Long userId, Long appId) {
        long used = dailyUsageMapper.sumTodayByUser(userId, LocalDate.now());
        if (used >= props.dailyTokenLimitPerUser()) {
            throw new BizException(UsageError.QUOTA_EXCEEDED);
        }
    }

    /** 落流水 + UPSERT daily_usage（配额） + UPSERT usage_stat_daily（看板）。三写同一 usage 本地事务。 */
    @Transactional
    public void recordUsage(TokenUsedEvent e) {
        llmCallLogMapper.insertLog(e.userId(), e.appId(), e.modelId(),
                e.promptTokens(), e.completionTokens(), e.source());
        long total = (long) e.promptTokens() + e.completionTokens();
        LocalDate today = LocalDate.now();
        dailyUsageMapper.upsertAccumulate(e.userId(), e.appId(), today, total);
        statDailyMapper.upsertAccumulate(e.userId(), e.appId(), e.modelId(), today,
                e.promptTokens(), e.completionTokens());
    }
}
