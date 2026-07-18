package com.hify.usage.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.common.page.CursorResult;
import com.hify.usage.dto.CallLogItem;
import com.hify.usage.mapper.LlmCallLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 调用日志明细查询（只读，管理后台）。查流水表是「分页明细」不是聚合，不违反
 * database-standards §聚合表代替扫流水；时间窗必填 ≤31 天保证分区裁剪。
 */
@Service
public class UsageLogService {

    private static final int MAX_WINDOW_DAYS = 31;
    private static final int MAX_LIMIT = 100;

    private final LlmCallLogMapper mapper;

    public UsageLogService(LlmCallLogMapper mapper) {
        this.mapper = mapper;
    }

    public CursorResult<CallLogItem> list(OffsetDateTime startTime, OffsetDateTime endTime,
                                          Long userId, Long appId, Long modelId, String source,
                                          String cursor, int limit) {
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new BizException(CommonError.PARAM_INVALID, "时间范围不合法");
        }
        if (Duration.between(startTime, endTime).toDays() > MAX_WINDOW_DAYS) {
            throw new BizException(CommonError.PARAM_INVALID, "时间范围不能超过 31 天");
        }
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        OffsetDateTime cursorTime = null;
        Long cursorId = null;
        if (StringUtils.hasText(cursor)) {
            LogCursor.Cursor c = LogCursor.decode(cursor);
            cursorTime = c.createTime();
            cursorId = c.id();
        }
        List<LlmCallLogMapper.CallLogRow> rows = mapper.selectPage(startTime, endTime,
                userId, appId, modelId, source, cursorTime, cursorId, size + 1);
        boolean hasMore = rows.size() > size;
        List<CallLogItem> list = rows.stream().limit(size)
                .map(r -> new CallLogItem(r.id(), r.userId(), r.appId(), r.modelId(),
                        r.promptTokens(), r.completionTokens(), r.source(), r.durationMs(), r.status(),
                        r.errorCode(), r.createTime()))
                .toList();
        String next = hasMore
                ? LogCursor.encode(list.get(list.size() - 1).createTime(), list.get(list.size() - 1).id())
                : null;
        return CursorResult.of(list, next, hasMore);
    }
}
