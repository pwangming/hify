-- V7：应用表（app 模块）。type 分对话型/工作流型；对话型绑 model_id + config(jsonb)；团队共享制带 owner_id。
-- 跨模块 model_id/owner_id 只存 id、不建外键（data-model.md 第 3 条）。

create table app (
    id          bigint      generated always as identity primary key,
    name        text        not null check (char_length(name) <= 50),
    description text        check (char_length(description) <= 200),
    type        text        not null check (type in ('chat', 'workflow')),
    model_id    bigint,
    config      jsonb       not null default '{}',
    owner_id    bigint      not null,
    status      text        not null default 'enabled' check (status in ('enabled', 'disabled')),
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table app is '应用（app 模块）：type 分对话型/工作流型；对话型绑 model_id + config(jsonb)；团队共享制带 owner_id';

-- 应用名团队内唯一（部分唯一索引，配合软删可同名重建）
create unique index app_name_uq on app (name) where deleted = false;
