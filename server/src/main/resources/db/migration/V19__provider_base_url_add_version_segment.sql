-- V19：baseUrl 约定翻转（修缮轮拍板，llm-resilience.md §6.1）。
-- openai 协议 baseUrl 从「不带版本段」改为「照抄厂商文档完整基址（含版本段）」，
-- ChatClientFactory 显式拼 /chat/completions、/embeddings，不再由框架默认拼 /v1/...。
-- 存量按旧约定填的行补回 /v1（deepseek、千问 compatible-mode 网关均适用）；not like 守卫防重复补。
update model_provider
set base_url    = rtrim(base_url, '/') || '/v1',
    update_time = now()
where protocol = 'openai'
  and rtrim(base_url, '/') not like '%/v1';
