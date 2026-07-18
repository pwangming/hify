-- V27：留账清理轮（usage 模块）。spec：2026-07-18-usage-cleanup-round-design.md。
-- ② llm_call_log 观测列（additive；分区父表 alter 自动传播到子分区）；
-- ① 配额检查已切 usage_stat_daily（V26 已从流水全量回填，daily_usage 无独有数据），废弃 daily_usage。

alter table llm_call_log
    add column duration_ms integer,
    add column status      text not null default 'success'
        check (status in ('success', 'failed')),
    add column error_code  text;
comment on column llm_call_log.duration_ms is '轮耗时毫秒；历史行为 null（V27 前无此列）';
comment on column llm_call_log.status is '调用结果；V27 前只有成功轮落行，default 回填历史行语义正确';
comment on column llm_call_log.error_code is '失败错误码：BizException 存错误码数字，其余存异常类简名；不存 message 防敏感信息入库';

drop table daily_usage;
