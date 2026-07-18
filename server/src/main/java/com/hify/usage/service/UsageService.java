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

    public void checkQuota(Long userId, Long appId) {
        long used = statDailyMapper.sumTodayByUser(userId, LocalDate.now());
        if (used >= props.dailyTokenLimitPerUser()) {
            throw new BizException(UsageError.QUOTA_EXCEEDED);
        }
    }

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
