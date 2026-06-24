-- V5：模型供应商表（provider 模块）。建表模板见 database-standards.md / V4：
-- 公共四列由 BaseEntity 承载；文本与枚举用 text + check，布尔用 boolean，时间用 timestamptz。
-- 本轮只做 Provider CRUD + Key 加密；韧性字段（max_concurrency 等）留后续轮次增量迁移。

create table model_provider (
    id             bigint      generated always as identity primary key,
    name           text        not null check (char_length(name) <= 50),
    protocol       text        not null check (protocol in ('openai', 'anthropic')),
    base_url       text        not null,
    api_key_cipher text        not null,
    api_key_tail   text        not null check (char_length(api_key_tail) <= 8),
    status         text        not null default 'enabled' check (status in ('enabled', 'disabled')),
    deleted        boolean     not null default false,
    create_time    timestamptz not null default now(),
    update_time    timestamptz not null default now()
);
comment on table model_provider is '模型供应商实例（provider 模块）：protocol 区分 OpenAI 兼容/Anthropic 两协议；api_key 加密存储';

-- 未软删的 name 唯一（配合 @TableLogic：软删后允许同名重建）。对齐 V4 sys_user 写法。
create unique index model_provider_name_uq on model_provider (name) where deleted = false;
