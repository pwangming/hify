-- V23：工具统一注册表（tool 模块）。source 分 builtin/openapi/mcp；
-- builtin 的 spec/owner_id 为空，name 即模型寻址标识与内置执行器绑定键。
-- app_tool_rel(T2)、mcp_server(T4) 另轮建表。

create table tool (
    id          bigint      generated always as identity primary key,
    name        text        not null check (char_length(name) <= 64),
    description text        not null check (char_length(description) <= 500),
    source      text        not null check (source in ('builtin', 'openapi', 'mcp')),
    enabled     boolean     not null default true,
    spec        jsonb,
    owner_id    bigint,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table tool is '工具统一注册表（tool 模块）：source 分 builtin/openapi/mcp；builtin 的 spec/owner_id 为空，name 即执行器绑定键';

-- 工具名唯一（部分唯一索引，配合软删可同名重建；模型按 name 寻址、Spring AI 要求名唯一）
create unique index tool_name_uq on tool (name) where deleted = false;

-- 播种 2 个内置工具（description 给模型看，直接影响其是否/如何调用）
insert into tool (name, description, source) values
  ('http_request',
   '发起一次 HTTP 请求并返回状态码与响应体。参数：method（GET/POST/PUT/DELETE/PATCH 之一）、url（完整 http/https 地址）、headers（可选，请求头对象）、body（可选，请求体字符串）。仅用于访问公网可达的 http/https 接口。',
   'builtin'),
  ('code_executor',
   '在隔离沙箱中执行一段 Python 代码并返回结果。你的代码必须定义一个无参函数 main() 并返回一个 dict（结果放进该 dict）。参数：code（完整 Python 源码字符串）。示例：def main():\n    return {"answer": 2 + 2}',
   'builtin');
