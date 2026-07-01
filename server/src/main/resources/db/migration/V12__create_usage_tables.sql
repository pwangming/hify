-- V12：用量与配额表（usage 模块）。data-model.md §1：llm_call_log(流水) + daily_usage(聚合)。
-- 跨模块 user_id/app_id/model_id 只存 id、不建外键（§3 条1）。Token 用 bigint（累计会超 21 亿，db-standards）。
-- 配额检查只查 daily_usage，禁止对 llm_call_log 实时聚合（db-standards §聚合表代替扫流水）。

-- 每次模型调用流水：高水位只增表，第一天就按月 RANGE 分区（db-standards §大表分级）。
-- 分区表铁律：主键必须含分区键 → pk=(id, create_time)。清理=drop 旧分区。
-- 本轮只由 conversation 成功轮的 TokenUsedEvent 落一行；耗时/失败等观测列待 provider 观测事件轮再 additive 加。
create table llm_call_log (
    id                bigint      generated always as identity,
    user_id           bigint      not null,
    app_id            bigint      not null,
    model_id          bigint      not null,
    prompt_tokens     bigint      not null default 0,
    completion_tokens bigint      not null default 0,
    create_time       timestamptz not null default now(),
    primary key (id, create_time)
) partition by range (create_time);
comment on table llm_call_log is '模型调用流水（usage 模块）：监听 TokenUsedEvent 落库；按 create_time 月分区，看板查此表勿实时聚合大范围';

-- 初始建 6 个月分区（2026-07 ~ 2026-12）；下月分区由应用内 @Scheduled 每月补建（db-standards §分区运维）。
create table llm_call_log_2026_07 partition of llm_call_log for values from ('2026-07-01') to ('2026-08-01');
create table llm_call_log_2026_08 partition of llm_call_log for values from ('2026-08-01') to ('2026-09-01');
create table llm_call_log_2026_09 partition of llm_call_log for values from ('2026-09-01') to ('2026-10-01');
create table llm_call_log_2026_10 partition of llm_call_log for values from ('2026-10-01') to ('2026-11-01');
create table llm_call_log_2026_11 partition of llm_call_log for values from ('2026-11-01') to ('2026-12-01');
create table llm_call_log_2026_12 partition of llm_call_log for values from ('2026-12-01') to ('2027-01-01');

-- 用户×应用×天 聚合：配额检查只查本表。累加走 UPSERT（db-standards §插或改一律 UPSERT，禁先查后插）。
-- id 用 bigint identity 主键（建表规范），(user_id,app_id,stat_date) 唯一约束作 on conflict 目标。
create table daily_usage (
    id           bigint      generated always as identity primary key,
    user_id      bigint      not null,
    app_id       bigint      not null,
    stat_date    date        not null,
    total_tokens bigint      not null default 0,
    create_time  timestamptz not null default now(),
    update_time  timestamptz not null default now()
);
comment on table daily_usage is '每日用量聚合（usage 模块）：user×app×天；配额检查只查本表，禁扫流水';

-- upsert 冲突目标：一天内同一 user+app 只一行
create unique index daily_usage_user_app_date_uq on daily_usage (user_id, app_id, stat_date);
-- 配额检查「某用户今日跨应用合计」的支撑索引：where user_id=? and stat_date=?
create index daily_usage_user_date_idx on daily_usage (user_id, stat_date);
