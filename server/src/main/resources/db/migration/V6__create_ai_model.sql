-- V6：具体模型表（provider 模块）。挂在 model_provider 下，区分 chat/embedding。
-- 建表模板见 V4/V5：text+check 枚举、boolean、timestamptz、bigint identity、公共四列由 BaseEntity 承载。

create table ai_model (
    id          bigint      generated always as identity primary key,
    provider_id bigint      not null references model_provider(id),
    type        text        not null check (type in ('chat', 'embedding')),
    name        text        not null check (char_length(name) <= 50),
    model_key   text        not null check (char_length(model_key) <= 100),
    status      text        not null default 'enabled' check (status in ('enabled', 'disabled')),
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table ai_model is '具体模型（provider 模块）：挂在 model_provider 下，区分 chat/embedding；model_key 为传给 LLM 的模型标识';

-- 同一供应商下同一模型标识不重复（配合 @TableLogic：软删后可同标识重建）。
create unique index ai_model_provider_key_uq on ai_model (provider_id, model_key) where deleted = false;
