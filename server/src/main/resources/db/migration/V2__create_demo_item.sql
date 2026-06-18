-- V2：CRUD 标准流程验证用的演示表（throwaway，验证完可加后续迁移 drop 掉）。
-- 建表模板见 database-standards.md 第 1.1 节：四个公共列每张表强制；name 用 text 加长度 check，
-- 时间用 timestamptz，布尔用 boolean。

create table demo_item (
    id          bigint generated always as identity primary key,
    name        text        not null check (char_length(name) <= 100),
    status      integer     not null,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table demo_item is 'CRUD 标准流程验证用的演示表（throwaway）';
