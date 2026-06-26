-- V10：会话与消息表（conversation 模块）。单轮先行、按多轮就绪建表（data-model.md §1）。
-- 跨模块 app_id/user_id 只存 id、不建外键（§3 条1）；模块内 message.conversation_id 建 FK 享级联删。

create table conversation (
    id          bigint      generated always as identity primary key,
    app_id      bigint      not null,
    user_id     bigint      not null,
    title       text        check (char_length(title) <= 100),
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table conversation is '会话（conversation 模块）：归属某用户、绑定某应用；个人数据仅本人可见';
create index conversation_user_idx on conversation (user_id, update_time desc) where deleted = false;

create table message (
    id                bigint      generated always as identity primary key,
    conversation_id   bigint      not null references conversation (id) on delete cascade,
    role              text        not null check (role in ('user', 'assistant')),
    content           text        not null,
    prompt_tokens     integer,
    completion_tokens integer,
    tool_calls        jsonb       not null default '[]',
    deleted           boolean     not null default false,
    create_time       timestamptz not null default now(),
    update_time       timestamptz not null default now()
);
comment on table message is '消息（conversation 模块）：role 分 user/assistant；tool_calls 预留 Agent 轨迹，本轮恒空';
create index message_conversation_idx on message (conversation_id, id) where deleted = false;
