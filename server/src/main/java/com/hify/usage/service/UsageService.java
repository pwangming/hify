package com.hify.usage.service;

import com.hify.common.event.TokenUsedEvent;
import com.hify.common.exception.BizException;
import com.hify.usage.config.UsageProperties;
import com.hify.usage.constant.UsageError;
import com.hify.usage.mapper.LlmCallLogMapper;
import com.hify.usage.mapper.UsageStatDailyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * usage 模块核心：入口配额检查（读）+ 用量落库（写）。
 *
 * <p>{@link #checkQuota} 只查 usage_stat_daily 聚合表（不扫流水；V27 起 daily_usage 已废弃），
 * 由入口同步调用；{@link #recordUsage} 由事件监听器异步调用：流水每事件都插（含失败行），
 * 聚合仅成功累加——配额与看板不被失败调用污染（留账清理轮 spec §3.3）。
 */
@Service
public class UsageService {

    private final LlmCallLogMapper llmCallLogMapper;
    private final UsageStatDailyMapper statDailyMapper;
    private final UsageProperties props;

    public UsageService(LlmCallLogMapper llmCallLogMapper, UsageStatDailyMapper statDailyMapper,
                        UsageProperties props) {
        this.llmCallLogMapper = llmCallLogMapper;
        this.statDailyMapper = statDailyMapper;
        this.props = props;
    }

    /**
     * 按用户/天封顶：今日已用 Token 合计 >= 上限即拦，抛 14001/429。
     * appId 本轮未参与判定（按用户封顶），保留供 per-app 配额扩展。
     */
    public void checkQuota(Long userId, Long appId) {
        long used = statDailyMapper.sumTodayByUser(userId, LocalDate.now());
        if (used >= props.dailyTokenLimitPerUser()) {
            throw new BizException(UsageError.QUOTA_EXCEEDED);
        }
    }

    /** 流水 + （仅成功）UPSERT usage_stat_daily。两写同一 usage 本地事务。 */
    @Transactional
    public void recordUsage(TokenUsedEvent e) {
        llmCallLogMapper.insertLog(e.userId(), e.appId(), e.modelId(), e.promptTokens(),
                e.completionTokens(), e.source(), e.durationMs(), e.success() ? "success" : "failed",
                e.errorCode());
        if (e.success()) {
            statDailyMapper.upsertAccumulate(e.userId(), e.appId(), e.modelId(), LocalDate.now(),
                    e.promptTokens(), e.completionTokens());
        }
    }
}
