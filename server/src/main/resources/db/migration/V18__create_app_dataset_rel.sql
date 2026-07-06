-- V18：应用↔知识库 多对多关系表（data-model.md 预留席位兑现，K4 绑定用）。
-- dataset_id 跨模块弱引用不建 FK（data-model 第 3 条）；app_id 模块内 FK 可建。
-- 绑定更新=全量替换（软删旧行+插新行），部分唯一索引只约束未删行。

create table app_dataset_rel (
    id          bigint      generated always as identity primary key,
    app_id      bigint      not null references app(id),
    dataset_id  bigint      not null,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table app_dataset_rel is '应用↔知识库多对多（app 模块）；dataset_id 跨模块弱引用';
create unique index app_dataset_rel_uq on app_dataset_rel (app_id, dataset_id) where deleted = false;
create index app_dataset_rel_dataset_idx on app_dataset_rel (dataset_id);
