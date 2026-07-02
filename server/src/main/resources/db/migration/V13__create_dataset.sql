-- V13：知识库表（knowledge 模块）。团队共享制带 owner_id；文档/分段表留 K2。
-- 跨模块 owner_id 只存 id、不建外键（data-model.md 第 3 条）。

create table dataset (
    id          bigint      generated always as identity primary key,
    name        text        not null check (char_length(name) <= 50),
    description text        check (char_length(description) <= 200),
    owner_id    bigint      not null,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table dataset is '知识库（knowledge 模块）：团队共享制带 owner_id；文档与分段见 kb_document/kb_chunk（K2）';

-- 知识库名团队内唯一（部分唯一索引，配合软删可同名重建）
create unique index dataset_name_uq on dataset (name) where deleted = false;
