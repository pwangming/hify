-- V1：启用数据库扩展（基线脚本）。
--
-- Hify 业务数据与向量同库（pgvector，见 CLAUDE.md / data-model.md），后续随各模块落地的
-- 建表脚本（V2 起）都建立在这里启用的扩展之上，所以扩展必须是第一个迁移。
--
-- 幂等写法 `if not exists`：Flyway 靠版本号保证每个脚本只跑一次，但加上它在手动重放、
-- 或不同环境已预装扩展时都不会报错，更稳妥。

-- pgvector：提供 vector 列类型与向量索引（HNSW）。knowledge 模块的 kb_chunk.embedding
-- 列、RAG 检索都依赖它。镜像 pgvector/pgvector:pg16 已预装，这里只是在本库里"启用"。
create extension if not exists vector;

-- 说明：pg_stat_statements（慢查询统计，database-standards.md 第 8 节）刻意不在此启用。
-- 它必须先由 PostgreSQL 服务端的 shared_preload_libraries 预加载才能 create extension，
-- 那是 postgres 容器的服务器参数（部署层，deployment.md），不属于应用迁移脚本的职责。
-- 待 compose 挂上 postgres 配置后，再用单独的迁移脚本启用它。
