# Hify 本地自检手册

> 每铺完一块基础组件，这里会追加一节"怎么自己验证它做对了"。
> 命令可直接在终端跑；在 Claude Code 输入框里命令前加 `!` 也能跑。
> 与 `docs/architecture/` 不同：那里讲"该怎么设计"，这里讲"怎么确认真的生效了"。

## 通用原则（先记住这三条）

1. **在改动真正落地的那一层去验证，别只看文件存在。**
   配置改了 → 查"配置有没有被程序加载"；迁移写了 → 查"数据库变没变"；
   光 `grep` 源文件只能证明"我写了"，证明不了"它生效了"。

2. **"我以为我改了" ≠ "文件真的变了"。** 改完用 `cat -A <文件>` 确认——
   它会把肉眼看不见的行尾空格（显示为 ` $`）、Tab（`^I`）全显形。
   ⚠️ 很多编辑器默认开"保存时删除行尾空格"，所以"加个空格做实验"经常无效，
   要做破坏性实验请改动可见内容（如加一整行）。

3. **反向验证：问自己"如果我把它弄坏了，这个检查会不会报警？"**
   不会报警的检查等于没检查。每节末尾的「故意搞错」就是用来确认守门机制是真的。

---

## 地基 1：数据库（连接池 + 超时 + Flyway/pgvector）

对应改动：`server/src/main/resources/application.yml`（HikariCP + statement_timeout）、
`server/src/main/resources/db/migration/V1__init_extensions.sql`（启用 pgvector）。

| 想确认什么 | 命令 | 正确输出 | 红灯 |
|---|---|---|---|
| 迁移脚本名字合法 | `ls server/src/main/resources/db/migration/` | 有 `V1__init_extensions.sql` | 文件名是 `V1_init`（单下划线）或小写 `v1` → Flyway 不认 |
| 我的改动真存进文件了 | `cat -A server/src/main/resources/db/migration/V1__init_extensions.sql` | 改动清晰可见 | 想改的内容看不到 → 没保存 / 被编辑器吞了 |
| pgvector 装上了 | `docker exec hify-postgres psql -U hify -d hify -c "\dx"` | 列表里有 `vector` | 只有 `plpgsql` → 迁移没生效 |
| Flyway 记账成功 | `docker exec hify-postgres psql -U hify -d hify -c "select version,description,success from flyway_schema_history;"` | `1 \| init extensions \| t` | 没有这行，或 `success=f` |
| 连接池参数加载对了 | `mvn -f server/pom.xml spring-boot:run -Dspring-boot.run.arguments="--logging.level.com.zaxxer.hikari=DEBUG"`（看完 `Ctrl+C`） | DEBUG 日志出现 `hify-pool - configuration:` 后跟 `maximumPoolSize...20`、`connectionTimeout...3000` | 启动报 `APPLICATION FAILED TO START` |

**两个容易踩的坑**：

- **看不到 Hikari 配置日志？** 那行 dump 是 **DEBUG 级别**，默认 INFO 下不打印；
  且本项目把池命名为 `hify-pool`（不是默认的 `HikariPool-1`）。必须像上表那样临时开 DEBUG 才看得到。
- **更省事的判断**：应用只要能正常启动，就证明连接池配置合法且被加载了——
  配置写错的话 Hikari 建连接就抛异常，Flyway 拿不到连接、整个应用根本起不来。
  "启动成功"本身就是一道隐形验证。

**故意搞错（确认 Flyway 校验真在守门）**：
给 `V1__init_extensions.sql` 加一整行注释（别用空格，会被编辑器吞），再 `mvn -f server/pom.xml spring-boot:run`。
应看到应用**拒绝启动**并报：

```
Validate failed: Migrations have failed validation
Migration checksum mismatch for migration version 1
```

这证明"已执行的迁移不可改"是真被强制的（也是 CLAUDE.md "迁移脚本只增不改" 的由来）。
**验证完把那行删掉**即可恢复（可用 `md5sum` 比对确认还原）。

---

## 地基 2：JWT 认证（infra 安全管道）

对应改动：`server/.../infra/security/`（8 个类）、`application.yml`（解开 Security + 加 `hify.security.jwt`）、
`deploy/.env.example`（加 `HIFY_JWT_SECRET`）。

### 自动化自检（首选，一条命令全测）

```bash
mvn -f server/pom.xml test
```
- ✅ 应看到 `BUILD SUCCESS`，且 `JwtServiceTest`(4)、`SecurityConfigTest`(6) 全过。
  这两个测试覆盖：发票/验票往返、过期→10003、篡改/换密钥→10002，以及放行/拦截/角色四类路由规则。
- ❌ 红灯：`BUILD FAILURE`，或某测试 `Failures > 0`。看 `server/target/surefire-reports/*.txt` 定位。

> `mvn test` 用的是 `@WebMvcTest` 切片，**不连数据库**，所以即使 postgres 没起也能跑。

### 运行时自检（用 curl 眼见为实，无需令牌）

先启动应用 `mvn -f server/pom.xml spring-boot:run`，另开一个终端：

| 检查点 | 命令 | 正确输出 |
|---|---|---|
| 放行路由 | `curl -i http://localhost:8080/api/v1/health` | `HTTP 200` + `{"code":200,...}` |
| 拦截生效 | `curl -i http://localhost:8080/api/v1/anything` | `HTTP 401` + `{"code":10002,...}` |
| 验票生效 | `curl -i -H "Authorization: Bearer 假票" http://localhost:8080/api/v1/anything` | `HTTP 401` + `{"code":10002,...}` |

注意 401 的响应体里也带 `traceId`——证明被拦截的请求一样能 grep 到日志。
（需要带真令牌走完整放行流程的 curl，要等 identity 模块的登录接口落地后再演示；
目前"有效令牌放行"由上面的自动化测试覆盖。）

### 故意搞错（确认安检真在守门）

把 `SecurityConfig` 里这行放行规则注释掉再重测：
```java
.requestMatchers("/api/v1/health").permitAll()
```
`SecurityConfigTest.健康检查_放行_无需令牌` 应**变红**（健康检查被要求登录、返回 401）。
这证明放行清单是真生效的，不是摆设。验证完恢复该行。

---

## 地基 3：异步事件基础设施（虚拟线程 + MDC 传递 + 日志格式）

对应改动：`server/.../infra/config/AsyncConfig.java`、`MdcTaskDecorator.java`、
`application.yml`（`spring.threads.virtual.enabled`、`logging.pattern.console`、`hify.async.concurrency-limit`）。

### 自动化自检

```bash
mvn -f server/pom.xml test
```
- ✅ `BUILD SUCCESS`，`AsyncConfigTest`(1)、`MdcTaskDecoratorTest`(2) 全过。
  覆盖：异步任务确实跑在**虚拟线程**上、发起线程的 **traceId 被传到异步线程**、任务结束后 MDC 被清理。
- ❌ 红灯：上述任一测试 `Failures > 0`。

### 运行时自检（看日志格式）

启动 `mvn -f server/pom.xml spring-boot:run`，观察控制台日志行形如：
```
2026-06-17 17:34:47.313 INFO  [] [main] com.zaxxer.hikari.pool.HikariPool - hify-pool - Added connection ...
```
- ✅ 每行是 `时间 级别 [traceId] [线程] 类 - 消息`；启动期没有请求，`[]` 为空是正常的。
- 处理请求时该位置会填入 traceId（与响应头 `X-Trace-Id`、`Result.traceId` 同一个值）。
- ❌ 红灯：日志还是带 `--- [hify-server]` 的旧默认格式，或没有 `[]` 槽位 → `logging.pattern.console` 没生效。

### 故意搞错（确认 traceId 真的靠装饰器传递）

把 `AsyncConfig.getAsyncExecutor()` 里这行注释掉再重测：
```java
executor.setTaskDecorator(new MdcTaskDecorator());
```
`AsyncConfigTest.异步任务_跑在虚拟线程且带上traceId` 应**变红**（异步线程里 traceId 变 null）。
这证明跨线程的 traceId 不是"碰巧有"，而是装饰器搬过去的。验证完恢复该行。

> ⚠️ **这个实验只能用 `mvn test` 验证，不能看 `spring-boot:run` 的日志**：
> 日志格式由 `logging.pattern.console` 决定，跟装饰器无关，注释掉装饰器日志格式不会变；
> 装饰器只在"有 @Async 任务真的在跑并打日志"时才起作用，而当前还没有任何 @Async 任务
> （usage 模块落地后才有），所以运行时日志里看不出差别。这正是"在改动真正生效的那一层验证"
> （通用原则第 1 条）的又一例。

---

## CRUD 标准流程验证（demo 模块，学习参考·长期保留）

`com.hify.demo`（DemoItem）演示"Controller→Service→Mapper→Entity→DTO"整链路，是**刻意长期保留的学习
参考模块**（不属于 10 个业务模块、禁止被业务模块依赖，见 code-organization.md 第 1 节）。新人读不懂复杂
业务模块时以它为对照。验收 curl（注意分页参数是 `size` 不是 `pageSize`，api-standards 第 3.1 节）：

```bash
# 1) 校验生效：name 为空 → 期望 400 + code 10001 + 字段错误
curl -i -X POST localhost:8080/api/v1/demo-items -H "Content-Type: application/json" -d '{"name":"","status":1}'
# 2) 正常创建 → 200，id 为字符串，create_time 自动填充，时间为 ISO-8601
curl -X POST localhost:8080/api/v1/demo-items -H "Content-Type: application/json" -d '{"name":"测试项","status":1}'
# 3) 分页列表 → PageResult{list,total,page,size}
curl "localhost:8080/api/v1/demo-items?page=1&size=10"
# 4) 逻辑删除 → 200；DB 里 deleted=t；列表不再出现
curl -X DELETE localhost:8080/api/v1/demo-items/1
docker exec hify-postgres psql -U hify -d hify -c "select id,deleted from demo_item order by id;"
```

> 📌 **从干净状态验证**：库里可能已有上次跑测试留下的数据（如 id=1 已软删、id=2 还在），
> 会影响你对"创建后 id、列表条数"的判断。想从零开始，先清空并重置自增 id：
> `docker exec hify-postgres psql -U hify -d hify -c "truncate demo_item restart identity;"`

> 🐞 **本次验证揪出的全项目级配置 bug（已修，记此为戒）**：BaseEntity 的 `deleted` 是 boolean 列，
> 但 MyBatis-Plus 默认逻辑删除值是整数 `1/0`，生成 `WHERE deleted = 0` 会被 PG 拒绝
> （`operator does not exist: boolean = integer`）。修复 = `application.yml` 配
> `mybatis-plus.global-config.db-config.logic-delete-value: "true"` / `logic-not-delete-value: "false"`。
> **这条配置对任何用软删的真实模块都必需**——即使删掉 demo 也要保留。教训：BaseEntity 的软删第一次
> 被真表真查询触发时才会暴露，所以"用一个最小 CRUD 跑通全链路"值得在写真业务前先做一遍。

> ⚠️ **Flyway 迁移脚本一旦执行过就当它是"只读"的（踩坑实录）**：改了 `V2` 的一句注释后重启，
> 启动直接挂在 `Migration checksum mismatch for migration version 2`（`Applied to database` 与
> `Resolved locally` 两个校验和对不上）。原因：Flyway 把每个已执行脚本的 CRC32 记在 `flyway_schema_history`
> 表里，启动先 validate；**哪怕只改注释、空格、换行，校验和都会变，启动就拒绝**。
> 正确做法（对齐 CLAUDE.md「数据库变更只通过新增 Flyway 脚本，禁止改已执行的旧脚本」）：
> **把旧脚本还原成执行时原样，要的变更（连表注释这种）一律新开下一个版本号**——本项目就是把 demo 表注释
> 的更新放进了 `V3__demo_item_keep_as_reference.sql`。
> 自检：`docker exec hify-postgres psql -U hify -d hify -c "select version,success,checksum from flyway_schema_history order by installed_rank;"`
> 看版本是否齐、`success` 是否全 `t`。

## 对外时间格式（全局 Jackson 序列化）

接口里所有 `OffsetDateTime` 字段（`createTime`/`updateTime` 等）统一由 `infra.config.JacksonConfig` 序列化，
规则两条：**① 固定到秒**（不输出纳秒小数）；**② 统一归一到 `hify.api.time-zone-offset`（默认 `+08:00`）**。
背景：数据库 `timestamptz` 经 JDBC 读出来是 UTC（`Z`），JVM 现生成的是本地偏移，两者风格不一；归一后对外只有一种风格。

### 自动化自检（首选）

```bash
cd server && mvn -o test -Dtest=JacksonConfigTest
# 关注两个用例：
#   时间固定到秒不输出纳秒  → 245072034ns 被截掉
#   UTC时间归一到东八区输出  → 06:50:19Z 输出成 14:50:19+08:00（同一时刻换偏移）
```

### 运行时自检（眼见为实）

```bash
# 任取一条 demo 数据看时间字段：必须形如 2026-06-18T14:50:19+08:00
curl "localhost:8080/api/v1/demo-items?page=1&size=10"
```

判定：`createTime` 满足三点才算对 —— ① 带 `T` 和 `+08:00`（ISO-8601 含偏移）；② 秒后**无小数**；
③ 不论新建回显还是列表读取，偏移**都是 `+08:00`**（不再出现 `Z` 或纳秒）。

> 💡 **为什么这不是 bug 而是规范**：`+08:00` / `Z` 都是同一绝对时刻，前端 `new Date(...)` 都能正确解析并按
> 浏览器时区显示。统一成 `+08:00` 纯为**风格一致**（对齐 api-standards 第 4 节示例），便于人读日志/排查。
> 不同时区部署改 `application.yml` 的 `hify.api.time-zone-offset` 即可，不动代码。
