-- V25：给存量 openapi 行的 spec 补 kind 标记（配合 T4a 引入的 ToolSpec 多态：Jackson 按 kind 分派
-- openapi/mcp 两种形状）。T3a 起落库的 openapi 行 jsonb 无 kind；ToolSpec 的 defaultImpl 已能兜底，
-- 本迁移让数据自身自洽，不长期依赖隐性兜底。builtin 行 spec 为 null，不受影响。
-- 用 jsonb_exists(spec,'kind') 而非 `spec ? 'kind'`：? 在 JDBC 语境与占位符同形，绕开省得踩坑。
update tool
   set spec = spec || '{"kind":"openapi"}'::jsonb
 where source = 'openapi'
   and spec is not null
   and not jsonb_exists(spec, 'kind');
