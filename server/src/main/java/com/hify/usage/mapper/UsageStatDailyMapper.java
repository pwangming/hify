package com.hify.usage.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * usage_stat_daily 看板聚合表访问（usage 模块）。写侧只有 UPSERT 累加
 * （database-standards §插或改一律 UPSERT）；读侧聚合查询见 UsageStatQueryMapper。
 */
public interface UsageStatDailyMapper {

    @Select("select coalesce(sum(prompt_tokens + completion_tokens), 0) from usage_stat_daily "
            + "where user_id = #{userId} and stat_date = #{statDate}")
    long sumTodayByUser(@Param("userId") Long userId, @Param("statDate") LocalDate statDate);

    @Insert("insert into usage_stat_daily (stat_date, user_id, app_id, model_id, "
            + "prompt_tokens, completion_tokens, call_count) "
            + "values (#{statDate}, #{userId}, #{appId}, #{modelId}, #{promptTokens}, #{completionTokens}, 1) "
            + "on conflict (user_id, app_id, model_id, stat_date) "
            + "do update set prompt_tokens = usage_stat_daily.prompt_tokens + excluded.prompt_tokens, "
            + "completion_tokens = usage_stat_daily.completion_tokens + excluded.completion_tokens, "
            + "call_count = usage_stat_daily.call_count + 1, update_time = now()")
    int upsertAccumulate(@Param("userId") Long userId, @Param("appId") Long appId,
                         @Param("modelId") Long modelId, @Param("statDate") LocalDate statDate,
                         @Param("promptTokens") long promptTokens, @Param("completionTokens") long completionTokens);
}
