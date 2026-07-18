package com.hify.usage.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * llm_call_log 流水表访问（usage 模块）。由 {@code @MapperScan("com.hify.**.mapper")} 注册，只被本模块 service 注入。
 *
 * <p>{@code id}(identity) 与 {@code create_time}(default now()) 交给 DB 生成——create_time 同时决定月分区路由，
 * 故不显式写入。看板读取此表待后续轮（本轮只写）。
 */
public interface LlmCallLogMapper {

    /** 落一行调用流水（成功轮的 TokenUsedEvent 触发，含 conversation/workflow 来源）。 */
    @Insert("insert into llm_call_log (user_id, app_id, model_id, prompt_tokens, completion_tokens, source) "
            + "values (#{userId}, #{appId}, #{modelId}, #{promptTokens}, #{completionTokens}, #{source})")
    int insertLog(@Param("userId") Long userId, @Param("appId") Long appId, @Param("modelId") Long modelId,
                  @Param("promptTokens") long promptTokens, @Param("completionTokens") long completionTokens,
                  @Param("source") String source);

    /**
     * 幂等补建月分区（DDL）。name/from/to 全由 {@code PartitionMaintainer} 从日期计算、非用户输入，
     * {@code ${}} 拼接安全（标识符/DDL 无法用 {@code #{}} 占位）。
     */
    @Update("create table if not exists ${name} partition of llm_call_log "
            + "for values from ('${from}') to ('${to}')")
    void createMonthlyPartition(@Param("name") String name, @Param("from") String from, @Param("to") String to);
}
