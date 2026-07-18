package com.hify.usage.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * llm_call_log 流水表访问（usage 模块）。由 {@code @MapperScan("com.hify.**.mapper")} 注册，只被本模块 service 注入。
 *
 * <p>{@code id}(identity) 与 {@code create_time}(default now()) 交给 DB 生成——create_time 同时决定月分区路由，
 * 故不显式写入。看板读取此表待后续轮（本轮只写）。
 */
public interface LlmCallLogMapper {

    record CallLogRow(Long id, Long userId, Long appId, Long modelId, long promptTokens,
                      long completionTokens, String source, OffsetDateTime createTime) {
    }

    /** 落一行调用流水（成功轮的 TokenUsedEvent 触发，含 conversation/workflow 来源）。 */
    @Insert("insert into llm_call_log (user_id, app_id, model_id, prompt_tokens, completion_tokens, source) "
            + "values (#{userId}, #{appId}, #{modelId}, #{promptTokens}, #{completionTokens}, #{source})")
    int insertLog(@Param("userId") Long userId, @Param("appId") Long appId, @Param("modelId") Long modelId,
                  @Param("promptTokens") long promptTokens, @Param("completionTokens") long completionTokens,
                  @Param("source") String source);

    /**
     * 游标分页明细（管理后台调用日志）。时间窗必选（分区裁剪）；(create_time,id) 行值比较降序翻页
     * （database-standards §4 游标分页）。cursorTime/cursorId 同为 null=首页。
     */
    @Select("""
            <script>
            select id, user_id, app_id, model_id, prompt_tokens, completion_tokens, source, create_time
            from llm_call_log
            where create_time &gt;= #{startTime} and create_time &lt; #{endTime}
            <if test="userId != null">and user_id = #{userId}</if>
            <if test="appId != null">and app_id = #{appId}</if>
            <if test="modelId != null">and model_id = #{modelId}</if>
            <if test="source != null">and source = #{source}</if>
            <if test="cursorTime != null">and (create_time, id) &lt; (#{cursorTime}, #{cursorId})</if>
            order by create_time desc, id desc
            limit #{limit}
            </script>
            """)
    List<CallLogRow> selectPage(@Param("startTime") OffsetDateTime startTime,
                                @Param("endTime") OffsetDateTime endTime,
                                @Param("userId") Long userId, @Param("appId") Long appId,
                                @Param("modelId") Long modelId, @Param("source") String source,
                                @Param("cursorTime") OffsetDateTime cursorTime,
                                @Param("cursorId") Long cursorId,
                                @Param("limit") int limit);

    /**
     * 幂等补建月分区（DDL）。name/from/to 全由 {@code PartitionMaintainer} 从日期计算、非用户输入，
     * {@code ${}} 拼接安全（标识符/DDL 无法用 {@code #{}} 占位）。
     */
    @Update("create table if not exists ${name} partition of llm_call_log "
            + "for values from ('${from}') to ('${to}')")
    void createMonthlyPartition(@Param("name") String name, @Param("from") String from, @Param("to") String to);
}
