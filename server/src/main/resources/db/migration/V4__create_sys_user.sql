-- V4：系统用户表（identity 模块）。建表模板见 database-standards.md 第 1.1 节：
-- 四个公共列每张表强制；文本用 text + 长度 check，时间用 timestamptz，布尔用 boolean。
-- 角色/状态用 text + check 约束枚举值（与代码里的小写枚举值对齐，不单独建角色表）。

create table sys_user (
    id            bigint generated always as identity primary key,
    username      text        not null check (char_length(username) <= 50),
    password_hash text        not null,
    role          text        not null check (role in ('admin', 'member')),
    status        text        not null default 'enabled' check (status in ('enabled', 'disabled')),
    deleted       boolean     not null default false,
    create_time   timestamptz not null default now(),
    update_time   timestamptz not null default now()
);
comment on table sys_user is '系统用户（identity 模块）：Admin/Member 用 role 字段区分，不单独建角色表';

-- 未软删用户的 username 唯一（配合 @TableLogic：软删后允许同名重新创建）。
create unique index sys_user_username_uq on sys_user (username) where deleted = false;
