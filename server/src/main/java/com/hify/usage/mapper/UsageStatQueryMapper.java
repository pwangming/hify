package com.hify.usage.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * usage_stat_daily 看板聚合读侧。行记录用 record 构造映射（先例 knowledge RetrievedChunk）。
 * aggregateByDimension 的 ${dimCol} 由 service 内固定 switch 提供（app_id/user_id/model_id 三值），
 * 非用户输入，${} 拼接安全（同 LlmCallLogMapper.createMonthlyPartition 先例）。
 */
public interface UsageStatQueryMapper {

    record ModelAgg(Long modelId, long promptTokens, long completionTokens, long callCount) {
    }

    record DailyModelAgg(LocalDate statDate, Long modelId, long promptTokens, long completionTokens,
                         long callCount) {
    }

    record DimModelAgg(Long targetId, Long modelId, long promptTokens, long completionTokens, long callCount) {
    }

    @Select("select model_id, sum(prompt_tokens) as prompt_tokens, "
            + "sum(completion_tokens) as completion_tokens, sum(call_count) as call_count "
            + "from usage_stat_daily where stat_date between #{start} and #{end} group by model_id")
    List<ModelAgg> aggregateByModel(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("select stat_date, model_id, sum(prompt_tokens) as prompt_tokens, "
            + "sum(completion_tokens) as completion_tokens, sum(call_count) as call_count "
            + "from usage_stat_daily where stat_date between #{start} and #{end} "
            + "group by stat_date, model_id order by stat_date")
    List<DailyModelAgg> aggregateDaily(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("select ${dimCol} as target_id, model_id, sum(prompt_tokens) as prompt_tokens, "
            + "sum(completion_tokens) as completion_tokens, sum(call_count) as call_count "
            + "from usage_stat_daily where stat_date between #{start} and #{end} "
            + "group by ${dimCol}, model_id")
    List<DimModelAgg> aggregateByDimension(@Param("dimCol") String dimCol,
                                           @Param("start") LocalDate start, @Param("end") LocalDate end);
}
