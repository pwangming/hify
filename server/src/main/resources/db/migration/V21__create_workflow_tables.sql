-- V21：workflow 三表（data-model.md §1）。跨模块 app_id/user_id 只存 id 不建外键（§3 条1），引用列必建索引（db-standards §2.1）。

-- 画布定义（草稿）。W1 每 app 恒一行 version=1；version 字段预留给发布轮。graph 整存整取不建 GIN（db-standards §2.4）。
create table workflow_def (
    id          bigint      generated always as identity primary key,
    app_id      bigint      not null,
    version     int         not null default 1,
    graph       jsonb       not null,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table workflow_def is '工作流画布定义（workflow 模块）：graph 为 jsonb 整存整取；(app_id,version) 软删内唯一，W1 只有 version=1 草稿';
create index workflow_def_app_id_idx on workflow_def (app_id);
create unique index workflow_def_app_version_uq on workflow_def (app_id, version) where deleted = false;

-- 运行实例（状态机）。同步执行无 pending；scaling-path 阶段 2 的任务表即本表，届时 check 加值即可。
create table workflow_run (
    id            bigint      generated always as identity primary key,
    app_id        bigint      not null,
    def_id        bigint      not null,
    user_id       bigint      not null,
    status        text        not null check (status in ('running', 'succeeded', 'failed')),
    inputs        jsonb,
    outputs       jsonb,
    error_message text,
    elapsed_ms    bigint,
    deleted       boolean     not null default false,
    create_time   timestamptz not null default now(),
    update_time   timestamptz not null default now()
);
comment on table workflow_run is '工作流运行实例（workflow 模块）：状态机 running→succeeded/failed；启动自愈把遗留 running 置 failed';
-- db-standards §2.2 点名标配（阶段 2 任务抢占 where status=? order by create_time）
create index workflow_run_status_create_time_idx on workflow_run (status, create_time);
-- 游标分页：where app_id=? and (create_time,id)<(?,?) order by create_time desc, id desc
create index workflow_run_app_ct_id_idx on workflow_run (app_id, create_time, id);
create index workflow_run_def_id_idx on workflow_run (def_id);
create index workflow_run_user_id_idx on workflow_run (user_id);
-- 状态机反复 UPDATE，autovacuum 调密（db-standards §8）
alter table workflow_run set (autovacuum_vacuum_scale_factor = 0.05, autovacuum_analyze_scale_factor = 0.02);

-- 节点执行日志：高水位只增表，第一天按月分区（db-standards §3）；照抄 V12 llm_call_log 模式，pk 必含分区键。
-- 日志无软删（清理=drop 分区），但有 update_time（running→终态一次 UPDATE 收尾）。
create table workflow_node_run (
    id            bigint      generated always as identity,
    run_id        bigint      not null,
    node_id       text        not null,
    node_type     text        not null,
    status        text        not null check (status in ('running', 'succeeded', 'failed')),
    inputs        jsonb,
    outputs       jsonb,
    error_message text,
    elapsed_ms    bigint,
    create_time   timestamptz not null default now(),
    update_time   timestamptz not null default now(),
    primary key (id, create_time)
) partition by range (create_time);
comment on table workflow_node_run is '节点执行日志（workflow 模块）：按 create_time 月分区；inputs=变量替换后的实际输入';
create index workflow_node_run_run_id_idx on workflow_node_run (run_id);

-- 初始建 6 个月分区（2026-07 ~ 2026-12）；下月分区由 WorkflowPartitionMaintainer 每月补建。
create table workflow_node_run_2026_07 partition of workflow_node_run for values from ('2026-07-01') to ('2026-08-01');
create table workflow_node_run_2026_08 partition of workflow_node_run for values from ('2026-08-01') to ('2026-09-01');
create table workflow_node_run_2026_09 partition of workflow_node_run for values from ('2026-09-01') to ('2026-10-01');
create table workflow_node_run_2026_10 partition of workflow_node_run for values from ('2026-10-01') to ('2026-11-01');
create table workflow_node_run_2026_11 partition of workflow_node_run for values from ('2026-11-01') to ('2026-12-01');
create table workflow_node_run_2026_12 partition of workflow_node_run for values from ('2026-12-01') to ('2027-01-01');
