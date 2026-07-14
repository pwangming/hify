-- V24：应用↔工具多对多（app 模块）。tool_id 跨模块弱引用 tool(id)，不建外键。
-- Agent 应用勾选启用哪些工具（T2）；agentEnabled 开关仍在 app.config jsonb。
create table app_tool_rel (
    id          bigint      generated always as identity primary key,
    app_id      bigint      not null references app(id),
    tool_id     bigint      not null,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table app_tool_rel is '应用↔工具多对多（app 模块）；tool_id 跨模块弱引用';
create unique index app_tool_rel_uq on app_tool_rel (app_id, tool_id) where deleted = false;
create index app_tool_rel_tool_idx on app_tool_rel (tool_id);
