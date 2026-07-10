# Hify 数据库性能规范（PostgreSQL 16 + pgvector）

> 建表、写 SQL、写 Flyway 脚本前必读。与本文冲突的 DDL/SQL 视为错误。
> 配套：表清单与关系见 `data-model.md`；"事务内禁外部 IO"见 `code-organization.md` 第 4.6 条。

## 1. 通用字段约定（每张表强制）

### 1.1 建表模板

```sql
create table example (
    id          bigint generated always as identity primary key,
    -- 业务列写在这里 --
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table example is '表用途一句话';
```

对应 `common.BaseEntity(id, createTime, updateTime, deleted)`，MyBatis-Plus 配置：
`IdType.AUTO`、`@TableLogic`（logic-delete-value=true / logic-not-delete-value=false）、
时间列由 `infra` 的 `MetaObjectHandler` 填充。

> ⚠️ `logic-delete-value=true / logic-not-delete-value=false` 必须配在 `application.yml` 的
> `mybatis-plus.global-config.db-config` 下。漏配则 MP 用默认整数 `1/0` 生成 `deleted = 0`，
> 而 `deleted` 是 boolean 列，PostgreSQL 报 `operator does not exist: boolean = integer`，
> 所有带软删过滤的查询/删除全部 500。

### 1.2 类型规则（违反即返工）

| 用途 | 用 | 不用 | 原因 |
|---|---|---|---|
| 主键/外联 id | `bigint identity` | `int`、随机 UUIDv4 | int 会溢出；UUIDv4 打散索引局部性 |
| 字符串 | `text`（需限长加 `check (char_length(x) <= n)`） | `varchar(n)` | 性能相同，少一类长度报错 |
| 时间 | `timestamptz` | `timestamp` | 时区安全 |
| 枚举 | `text` + `check (status in (...))` | PG `enum` 类型 | 加枚举值只改 check，不用专门 DDL |
| 布尔 | `boolean` | `smallint`/字符串 | — |
| 结构化配置/画布 | `jsonb` | `json`、`text` 存 JSON | 可索引、可部分更新 |
| Token 数 | `bigint` | `int` | 累计值会超 21 亿 |
| 金额/成本 | `numeric(12,6)` | `float` | 精确算术 |
| 文件二进制 | `bytea`（≤ 50MB，对齐上传限制） | large object、文件系统路径 | 随 pg_dump 备份、多副本天然共享；lo 需 vacuumlo 额外清理 |

### 1.3 命名与暴露

- 全小写 snake_case；索引命名 `<表>_<列>_idx`，唯一索引 `_uq`，check 约束 `_ck`。
- **对外 API 出现的标识不用自增主键**（可被枚举遍历）：`app_api_key.token`、
  对外的会话标识等用独立随机串列（`gen_random_uuid()` 或随机 token），自增 id 只在站内用。

## 2. 索引设计原则

1. **每个引用 id 列必建索引**——PG 不会自动给 FK 建索引，跨模块弱引用列（无 FK 约束）更不会。
   `conversation.app_id`、`message.conversation_id`、`kb_chunk.document_id`、
   `llm_call_log.app_id`… 一律建。Flyway 脚本评审第一查这个。
2. **复合索引：等值列在前，范围/排序列在后**（最左前缀原则）。Hify 的三个标配：
   `message(conversation_id, id)`、`llm_call_log(app_id, create_time)`、
   `workflow_run(status, create_time)`（任务抢占查询 `where status='pending' order by create_time`）。
3. **软删 + 唯一约束必须用部分唯一索引**：
   ```sql
   create unique index sys_user_username_uq on sys_user (username) where deleted = false;
   ```
   普通 unique 会让"删掉重建同名资源"永远失败。所有业务唯一性（用户名、知识库名、应用名）统一此写法。
4. **jsonb 默认不建索引**。`workflow_def.graph`、`app.config` 都是整存整取，建 GIN 纯属写放大。
   只有出现"按 jsonb 内部字段过滤"的真实查询时才加 GIN/表达式索引。
5. **covering index（INCLUDE）和其余锦上添花的索引，只在 EXPLAIN ANALYZE 证明热点后加**。
   索引不是越多越好：每加一个，所有写入都付一份代价。建表初期只建"已知查询路径"的索引。
6. **每条新查询路径上线前过一次 `EXPLAIN ANALYZE`**，出现 Seq Scan 于大表即不通过。
   缺失 FK 索引的巡检 SQL 已附在本文附录。

### 2.1 pgvector 专项

- 全库统一 embedding 模型与维度（系统级设置）；`kb_chunk.embedding vector(<dim>)` 维度写死。
  **换 embedding 模型 = 全量重嵌入**，是 admin 显式操作，不做多维度共存。
- 索引固定 HNSW + cosine：
  ```sql
  create index kb_chunk_embedding_idx on kb_chunk
      using hnsw (embedding vector_cosine_ops);  -- m/ef_construction 用默认值
  ```
- 检索 SQL 模板固定为"先过滤后排序"：
  ```sql
  select id, document_id, content from kb_chunk
  where dataset_id = any(?) and deleted = false
  order by embedding <=> ? limit ?;
  ```
  `dataset_id` 必有 b-tree 索引（原则 1）。检索 SQL 在 Mapper 手写注解 SQL（K4 拍板：`kb_chunk`
  自建表与 Spring AI `PgVectorStore` 表结构不兼容，Advisor 抽象对单一消费方无收益）；多库过滤用
  MyBatis foreach `in (...)`，与 `any(?)` 等价。相似度阈值在 Java 层过滤，不进 where（避免干扰
  HNSW 索引走法）。
- **首次大批量导入：先插数据再建 HNSW 索引**（快一个量级）；日常增量直接插。
- JDBC 连接串加 `reWriteBatchedInserts=true`；chunk 批量写入用**多值 insert**（MyBatis foreach 拼
  values，一条 SQL 插 N 行，每批 ≤ 1000 行）。禁用 MP 静态 `Db.saveBatch`：静态工具在多 Spring
  上下文下会拿错 SqlSessionFactory（测试随机挂），且其 BATCH 执行器无法与同事务内的 SIMPLE
  执行器共存，实际不参与当前事务。（W1 终审拍板，2026-07-10）
- **召回不足先调查询参数**：会话级 `set local hnsw.ef_search = 100`（默认 40，代价是延迟），
  禁止靠重建索引改 m/ef_construction 解决召回问题。文档大量删除后 dead tuple 会拖慢 HNSW 扫描，
  批量删除后手动 `vacuum analyze kb_chunk`。

## 3. 大表预判与应对

按 data-model.md 的表分三级：

| 级别 | 表 | 预判量级（千人阶段） | 策略 |
|---|---|---|---|
| 高水位只增 | `llm_call_log`、`workflow_node_run` | 千万行/年 | **第一天就按月 RANGE 分区**（create_time）；清理=drop partition |
| 增长可观 | `message`、`kb_chunk`、`workflow_run` | 百万~千万 | 先不分区；触发线见下 |
| 常规 | 其余全部 | 几十万以内 | 什么都不做 |

- 分区运维：Flyway 初始建 6 个月分区 + 应用内每月定时任务建下月分区
  （`create table if not exists ... partition of`），不引 pg_partman。
- 第二级表的动手触发线：**单表 > 3000 万行，或相关查询 P95 翻倍**。届时 `message` 按月分区、
  `kb_chunk` 按 dataset 分区（scaling-path.md 阶段 3 已有此项）。
- **聚合表代替扫流水**：看板/配额永远查 `daily_usage`，禁止对 `llm_call_log` 做实时聚合。
- **大表禁精确 count**：列表页总数用 `pg_class.reltuples` 估算或干脆不显示总页数（见第 4 节）。
- 大批量写入后手动 `analyze <table>`，别等 autovacuum（统计失真会让 planner 选错计划）。

## 4. 分页查询规范

按场景二选一，不允许混用：

**管理后台列表（浅分页，Element Plus 页码 UI）** —— 允许 OFFSET（MyBatis-Plus `Page<T>`），但三条强制：
1. `order by` 必须落在索引上且以 `id` 结尾保证稳定排序；
2. 限制页深：`page × size ≤ 10_000`，超出直接报"请用筛选条件缩小范围"；
3. 分区/高水位表关掉 `Page` 的 count 查询（`Page.of(n, size, false)`），总数用估算或不显示。

**消息流、运行日志、对外 API（可能深翻）** —— 必须游标分页（keyset）：
```sql
-- 排序键必须含唯一列 id，否则翻页丢行/重行
select ... from message
where conversation_id = ? and (create_time, id) < (?, ?)
order by create_time desc, id desc limit ?;
```
接口返回 `next_cursor`（最后一行的 create_time+id 编码），不接受页码参数。
配套复合索引即第 2 节标配 `message(conversation_id, id)` 这类。

**三条通用禁令**：
- 列表查询禁 `select *`：`kb_chunk` 的 `embedding`（几 KB/行）和 `content`、`message` 的
  `content`、`workflow_def` 的 `graph`、`kb_document` 的原始文件 bytea（可达 50MB/行）
  都是大列，列表场景必须用 MP 的 `.select(...)` 显式列清单；
- 禁止用分页循环做全表处理（O(n²)）——批处理用 keyset 游标或 `Db` 流式；
- 禁止 N+1：循环里逐条 `selectById` 一律改为按 id 集合一次批量查
  （MP `selectBatchIds` / `in(...)`，SQL 用 `= any(?)`），关联数据在 service 层一次装配。

## 5. 连接池与超时（HikariCP / PG 双侧设防）

连接是最稀缺资源；虚拟线程放大了"把连接当线程用"的风险。本节与 code-organization.md
第 4.6 条（事务内禁外部 IO）互为表里：那条防业务代码占住连接，本节在基础设施层兜底。

1. **池要小且固定**：HikariCP `maximumPoolSize=20`（与 llm-resilience.md 第 1 节、
   scaling-path 阶段 1 的 20→40 对齐），`minimumIdle` 同值不伸缩。**虚拟线程再多也不加池**——
   上万虚拟线程共享 20 个连接靠池内排队；池耗尽报警时先查"事务内外部 IO"违规，
   加大池只是把雪崩转嫁给 PG。
2. **快速失败**：`connectionTimeout=3s`（拿不到连接立刻报错暴露问题，不静默排队）；
   `maxLifetime=30min`，避开中间网络设备的空闲断连。
3. **PG 侧 `max_connections=50`**：单副本 20 + 运维/备份/巡检余量；多副本时按副本数重算（scaling-path 阶段 1）。
4. **SQL 超时三层**（全部外化为配置，不硬编码——CLAUDE.md 行为指令）：

   | 层 | 参数（设在哪） | 值 | 作用 |
   |---|---|---|---|
   | 语句 | `statement_timeout`（数据源 init SQL） | 30s | 失控查询硬上限；批量任务用 `set local` 临时放宽 |
   | 事务空闲 | `idle_in_transaction_session_timeout`（PG 全局） | 60s | 兜底击杀"事务内做外部调用"的违规连接 |
   | 锁等待 | `lock_timeout`(仅 DDL/迁移会话) | 5s | DDL 拿不到锁快速失败重跑，不排队堵死业务 |

## 6. 事务与锁

1. **写操作压缩到事务末尾**：校验、查询、计算放事务外或前段，行锁持有毫秒级。
2. **多行更新按主键升序加锁**：`select ... where id = any(?) order by id for update` 再更新，
   或合并为单条 update。出现死锁先查加锁顺序，不是加重试。
3. **存在唯一约束的"插或改"一律 UPSERT**，禁止先查后插（必有竞态）：
   ```sql
   -- daily_usage 聚合累加固定模板（system_setting KV 同理）
   insert into daily_usage (user_id, app_id, stat_date, total_tokens)
   values (?, ?, ?, ?)
   on conflict (user_id, app_id, stat_date)
   do update set total_tokens = daily_usage.total_tokens + excluded.total_tokens;
   ```
4. **热点行并发累加用单条原子 update**（`set x = x + ?`），禁止读-改-写三步。
5. **任务抢占固定 SKIP LOCKED 模板**（`workflow_run` 状态机，多 worker/多副本互不阻塞）：
   ```sql
   update workflow_run
   set status = 'running', update_time = now()
   where id = (
       select id from workflow_run
       where status = 'pending'
       order by create_time
       limit 1
       for update skip locked)
   returning *;
   ```

## 7. DDL 与迁移安全（Flyway）

已合并的迁移脚本只增不改（CLAUDE.md 行为指令）。对**已有数据的表**做 DDL：

1. **建索引一律 `create index concurrently`**（脚本头加 `-- flyway:executeInTransaction=false`，
   该语句不能跑在事务里）；失败会残留 INVALID 索引，重跑前先 drop。初始建表脚本不受此限。
2. **加列安全，改类型危险**：`add column ... default ...` 在 PG11+ 不重写表，随便加；
   `alter column type` 重写全表持排他锁——大表禁止，走"加新列 → 分批回填 → 切换 → 删旧列"。
3. **加约束分两步**：`add constraint ... not valid`（瞬间）→ `validate constraint`（轻锁扫描）。
   一步到位的写法会在全表扫描期间持锁。
4. **回填数据分批提交**：keyset 游标循环、每批 ≤ 5000 行独立事务；
   禁止单条 update 刷全表（长锁 + WAL 洪峰）。
5. 迁移会话必设 `lock_timeout=5s`（第 5 节）：宁可迁移失败重跑，不可堵死线上。

## 8. 服务器参数与例行维护基线

postgres 容器按 6G 内存定参（deployment.md 资源表），写进 compose 挂载的 conf，不用默认值：

| 参数 | 值 | 说明 |
|---|---|---|
| `shared_buffers` | 1.5GB | 内存的 25% |
| `effective_cache_size` | 4GB | 告诉 planner 还有 OS 缓存可用 |
| `work_mem` | 16MB | 排序/哈希用；× 实际并发不超总内存 1/4 |
| `maintenance_work_mem` | 512MB | HNSW/索引构建、vacuum 直接受益 |
| `max_wal_size` | 2GB | 减少 checkpoint 风暴 |
| `log_min_duration_statement` | 1s | 慢 SQL 自动进日志 |
| `log_lock_waits` | on | 锁等待超 deadlock_timeout 进日志 |

- **pg_stat_statements 第一天就开**（`shared_preload_libraries`），每月例行看一次
  top 10 `total_exec_time`（巡检 SQL 见附录），配合第 2.6 条 EXPLAIN 验证。
- **autovacuum 对高频 UPDATE 表调密**：`workflow_run`（状态机反复改）设
  `autovacuum_vacuum_scale_factor=0.05`、`autovacuum_analyze_scale_factor=0.02`；其余表用默认。
  大批量写后手动 `analyze`（第 3 节已有）。
- **应用账号最小权限**：连接账号禁 superuser。一期 Flyway 与业务共用一个普通账号（库 owner）可接受；
  多副本或有发布流程后再拆"迁移账号 / 运行账号"。

## 附录：例行巡检 SQL

```sql
-- 1. 找有 FK 约束但没索引的列（弱引用列靠 code review，因为没有约束可查）
select conrelid::regclass as table_name, a.attname as fk_column
from pg_constraint c
join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any(c.conkey)
where c.contype = 'f'
  and not exists (select 1 from pg_index i
                  where i.indrelid = c.conrelid and a.attnum = any(i.indkey));

-- 2. 月度慢查询 top 10（需 pg_stat_statements，见第 8 节）
select calls,
       round(mean_exec_time::numeric, 1)  as mean_ms,
       round(total_exec_time::numeric)    as total_ms,
       left(query, 120)                   as query
from pg_stat_statements
order by total_exec_time desc
limit 10;

-- 3. 残留的 INVALID 索引（concurrently 建索引失败的遗留，见第 7.1 条）
select indexrelid::regclass as index_name
from pg_index where not indisvalid;
```
