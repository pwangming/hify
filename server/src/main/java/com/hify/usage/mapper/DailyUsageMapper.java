package com.hify.usage.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * daily_usage 聚合表访问（usage 模块）。由 {@code @MapperScan("com.hify.**.mapper")} 注册，只被本模块 service 注入。
 *
 * <p>本项目首处注解式自定义 SQL：{@code daily_usage} 的累加是 PG UPSERT（on conflict），
 * MyBatis-Plus Wrapper 表达不了，故用 {@code @Insert}/{@code @Select}，不引 XML（database-standards 固定模板）。
 */
public interface DailyUsageMapper {

    /** 某用户今日跨应用已用 Token 合计（配额检查专用；空结果 coalesce 为 0）。走 (user_id, stat_date) 索引。 */
    @Select("select coalesce(sum(total_tokens), 0) from daily_usage "
            + "where user_id = #{userId} and stat_date = #{statDate}")
    long sumTodayByUser(@Param("userId") Long userId, @Param("statDate") LocalDate statDate);

    /** 累加落库：同 (user, app, 天) 已存在则加，否则插入。UPSERT 避免先查后插竞态（database-standards §插或改）。 */
    @Insert("insert into daily_usage (user_id, app_id, stat_date, total_tokens) "
            + "values (#{userId}, #{appId}, #{statDate}, #{tokens}) "
            + "on conflict (user_id, app_id, stat_date) "
            + "do update set total_tokens = daily_usage.total_tokens + excluded.total_tokens, "
            + "update_time = now()")
    int upsertAccumulate(@Param("userId") Long userId, @Param("appId") Long appId,
                         @Param("statDate") LocalDate statDate, @Param("tokens") long tokens);
}
