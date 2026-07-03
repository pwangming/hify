-- V16：系统设置 KV 表（data-model 预留席位兑现），当前归 provider 模块管理。
-- K3 仅一个键 embedding_model_id；后续系统设置复用本表，归属届时再议。
-- 照 database-standards §1.1 模板（四标准列强制）；业务唯一性用部分唯一索引（§2 条 3）。

create table system_setting (
    id            bigint      generated always as identity primary key,
    setting_key   text        not null check (char_length(setting_key) <= 100),
    setting_value text,
    deleted       boolean     not null default false,
    create_time   timestamptz not null default now(),
    update_time   timestamptz not null default now()
);
comment on table system_setting is '系统设置 KV（admin 可改）；K3 起用，键：embedding_model_id';
create unique index system_setting_key_uq on system_setting (setting_key) where deleted = false;
