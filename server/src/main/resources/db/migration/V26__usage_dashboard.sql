-- V26：用量与成本看板（usage/provider 模块）。spec：2026-07-17-admin-usage-dashboard-design.md。
-- ① ai_model 单价（元/百万 token，可空=未配价，0=免费）；② llm_call_log 补来源列（历史行留 null）；
-- ③ 看板聚合表（database-standards §聚合表代替扫流水：看板查此表，不扫流水）；④ 存量回填。

-- ① 模型单价（provider 模块）
alter table ai_model
    add column input_price  numeric(10,4) check (input_price >= 0),
    add column output_price numeric(10,4) check (output_price >= 0);
comment on column ai_model.input_price is '输入单价，元/百万token；null=未配置（费用计0并标注不完整）';
comment on column ai_model.output_price is '输出单价，元/百万token；null=未配置';

-- ② 调用来源（usage 模块；分区父表 alter 自动传播到子分区）
alter table llm_call_log
    add column source text check (source in ('conversation', 'workflow'));
comment on column llm_call_log.source is '调用来源；历史行为 null（V26 前无此列）';

-- ③ 看板聚合表：日×用户×应用×模型。prompt/completion 拆开存（输入输出单价不同才算得了钱）。
--    call_count=成功轮次数（一条 TokenUsedEvent 计 1，Agent 一轮内部多次 LLM 调用算 1 次）。
--    普通表不分区（行数=活跃维度组合，量级小）；stat_date 按北京时间归日（与 daily_usage 口径一致）。
create table usage_stat_daily (
    id                bigint      generated always as identity primary key,
    stat_date         date        not null,
    user_id           bigint      not null,
    app_id            bigint      not null,
    model_id          bigint      not null,
    prompt_tokens     bigint      not null default 0,
    completion_tokens bigint      not null default 0,
    call_count        bigint      not null default 0,
    create_time       timestamptz not null default now(),
    update_time       timestamptz not null default now()
);
comment on table usage_stat_daily is '看板聚合表（usage 模块）：日×用户×应用×模型；监听器与流水同事务 UPSERT；看板只查此表';
create unique index usage_stat_daily_dim_uq
    on usage_stat_daily (user_id, app_id, model_id, stat_date);
create index usage_stat_daily_date_idx on usage_stat_daily (stat_date);

-- ④ 存量回填：历史流水一行不丢（UsageDashboardMigrationTest 有同文本 SQL 验证语义，改此处必须同步测试）
insert into usage_stat_daily (stat_date, user_id, app_id, model_id,
                              prompt_tokens, completion_tokens, call_count)
select (create_time at time zone 'Asia/Shanghai')::date as stat_date,
       user_id, app_id, model_id,
       sum(prompt_tokens) as prompt_tokens,
       sum(completion_tokens) as completion_tokens,
       count(*) as call_count
from llm_call_log
group by 1, 2, 3, 4;
