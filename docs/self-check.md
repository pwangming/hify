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

---

## 前端地基 1：登录态 store（useUserStore）

对应改动：`web/src/stores/user.ts`（store）、`web/src/api/auth.ts`（`getCurrentUser`）、
`web/src/types/user.ts`（`UserInfo` 类型）。这是前端第 0 层「应用外壳」的第一块——后续布局顶栏、
路由守卫都依赖它拿登录态与角色。

**设计要点（为什么这么搭）：**
- **token 唯一来源**是 `localStorage` 的 `hify_token` 键。store 负责写，`request.ts` 直接读（不 import store），
  以此打断 `request ←→ store` 循环依赖；两边复用 `request.ts` 导出的 `TOKEN_KEY` 常量，保证读写同一个键。
- **只持久化 token**；`user` 信息是内存态，刷新后由守卫 `loadCurrentUser()` 重新拉回（对齐 frontend-standards 第 6 节）。

### 自动化自检（首选）

`web/` 暂未引入单测 runner（vitest 属新依赖，未装），当前的自动化关卡是**类型检查 + 构建**：

```bash
cd web && pnpm build   # = vue-tsc --noEmit（全量类型检查）+ vite build
```

判定：编出 `dist/` 且无 TS 报错即过。`vue-tsc` 按 tsconfig 检查**所有**文件——哪怕 store 还没被任何页面
引用，类型错误或循环依赖也会现形。

### 运行时自检（浏览器控制台眼见为实）

store 还没接进页面，但可在浏览器控制台直接验证「读写同一个键」这条命脉：

```js
localStorage.setItem('hify_token', 'fake.jwt.token') // 模拟 store.setToken
// 刷新页面 → store 的 token 初值就从这里读回（isLoggedIn 应为 true）
localStorage.getItem('hify_token')                   // → 'fake.jwt.token'
localStorage.removeItem('hify_token')                // 模拟 store.logout，键应被清掉
```

关键确认：`request.ts` 注入 JWT 时读的、store 持久化时写的，是**同一个 `hify_token` 键**（都源自
`TOKEN_KEY` 常量），不会各存一份对不上。

### 故意搞错（确认关卡真在守门）

把 `types/user.ts` 的 `id: string` 改成 `id: number`，再在任意会把 id 当字符串用的地方（如将来拼进 URL）引用——
`vue-tsc` 会报类型不符，证明**类型关卡有效**。
反例提醒：把 `isAdmin` 的 `role === 'admin'` 误写成大写 `'ADMIN'`，build **不会**报错（只是值比较），
但 `isAdmin` 永远 false——这类"值写错"类型层挡不住，要留到守卫接好后**登录看菜单是否按角色出现**来兜底验证。

---

## 前端地基 2：测试基建（vitest）+ 布局骨架

对应改动：`web/` 引入 vitest（`vitest` / `@vue/test-utils` / `happy-dom`，配在 `vite.config.ts` 的 `test` 段）、
`web/src/layouts/DefaultLayout.vue` + `BlankLayout.vue` 及其 `__tests__/` 测试。
约定：**测试就近放各目录 `__tests__/` 下**（`tsconfig` 已 `exclude` 此目录，测试由 vitest 跑、不进 `vue-tsc` 构建）。

**两层布局的分工（为什么两个）：**
- `DefaultLayout`：后台主框架（侧栏 + 顶栏用户名/退出 + 内容 `<slot/>`）。内容用插槽而非内置 `RouterView`，
  好让登录页能换 `BlankLayout`——布局由 App.vue 按路由 `meta.layout` 选（在守卫/登录步骤接上）。
- `BlankLayout`：无壳，仅透传插槽，登录页用。

### 自动化自检（首选）

```bash
cd web && pnpm test     # vitest run：跑所有 src/**/__tests__/*.spec.ts
cd web && pnpm build    # vue-tsc 类型检查 + 构建（组件类型不过会红）
```

判定：`pnpm test` 全绿（当前 9 个：store 6 + 布局 3）；`pnpm build` 编出 `dist/` 无报错。
`pnpm test:watch` 可在开发时常驻、改文件即重跑。

### 这一步遵循的 TDD 纪律（眼见为实）

布局是全新代码，按红→绿写：**先写 `DefaultLayout.spec.ts` / `BlankLayout.spec.ts`，跑出 RED
（“找不到 `*.vue` 模块”=功能缺失），再建组件转 GREEN**。两个核心行为被测住：
顶栏显示当前用户名；点“退出”→ 清 `token`+`localStorage` 且跳 `/login`。

> 💡 store 的 6 个测试是**对既有代码补的刻画测试**（store 上一步已写好），首跑即绿——这是允许的；
> 真正的红→绿只对新代码（布局）要求。

### 故意搞错（确认测试真在守门）

把 `DefaultLayout.vue` 的 `handleLogout` 里 `userStore.logout()` 注释掉再 `pnpm test`——
“点击退出清登录态”用例应变红（`store.token` 仍非空）。能变红，才证明这条测试不是摆设；验完记得改回。

---

## 前端地基 3：侧边菜单按路由 + 角色生成，App 按 meta.layout 选布局

对应改动：`web/src/router/menu.ts`（`isRoleAllowed` / `buildMenu` 纯函数）、
`web/src/types/router.d.ts`（`RouteMeta` 契约：`roles/title/menu/layout`）、
`DefaultLayout.vue` 菜单改为 `v-for` 动态生成、`App.vue` 按 `meta.layout` 选布局（退役内联临时布局）、
`router/index.ts` 三条路由补 `meta.menu: true`。

**设计意图（为什么）：**
- 菜单**从路由表派生**：路由加一条带 `meta.menu` 的记录，菜单自动多一项；`meta.roles` 决定谁可见
  （Member 看不到 `roles:['admin']` 的项）。规则集中在路由表，一眼可审"哪些页面仅 Admin"。
- "看得到 = 进得去"：菜单与守卫（第 4 步）共用同一个 `isRoleAllowed`，避免两套判断打架。
- `App.vue` 用 `<component :is="layout"><RouterView/></component>` 按 `meta.layout` 切布局，
  登录页将来标 `meta.layout:'blank'` 即走无壳布局。

### 自动化自检（首选）

```bash
cd web && pnpm test    # 全绿（当前 18：store 6 + 布局 5 + menu 5 + App 2）
cd web && pnpm build   # 类型检查（含 RouteMeta 增强）+ 构建
```

### 运行时自检（眼见为实）

`pnpm dev` 起开发服务器，浏览器看左侧菜单：

- 未登录/角色未知时，仅"知识库管理""应用管理"两项（admin 专属的"模型提供商管理"被过滤）。
- 在控制台手动给 store 设个 admin 用户，菜单应多出"模型提供商管理"：
  ```js
  // 开发期临时验证角色过滤（守卫接好后由 /me 自动填充）
  // 通过 Vue Devtools 把 user store 的 user 设为 { id:'1', username:'a', role:'admin' }
  ```
  Member 角色则不应出现该项。

### 故意搞错（确认关卡真在守门）

把 `menu.ts` 的 `isRoleAllowed` 改成永远 `return true`，`pnpm test`——
"Member 看不到 admin 专属项"用例应变红（Member 也看到了"模型提供商管理"）。能变红即守门有效；验完改回。

---

## 前端地基 4：登录态守卫 + 登录页 + 错误页（主干合龙）

对应改动：`web/src/router/guard.ts`（`authGuard`，规范 7.2 五步）、`router/index.ts`（挂
`beforeEach(authGuard)` + `afterEach` 设标题，新增 login/403/404 路由）、`views/login/LoginView.vue`、
`views/error/ForbiddenView.vue` + `NotFoundView.vue`、`api/auth.ts` 增 `login()`、`types/user.ts` 增登录 DTO。

**这一步打通的主干**：未登录访问受保护页 → 跳 `/login?redirect=原目标` → 登录成功写 token、拉 `/me`
拿角色 → 跳回原目标 → 按角色看菜单 → 退出。守卫与菜单共用 `isRoleAllowed`，"看得到=进得去"。

### 自动化自检（首选）

```bash
cd web && pnpm test    # 全绿（当前 28：含 guard 6 + login 2 + 错误页 2）
cd web && pnpm build   # 类型检查 + 构建
```

守卫六条分支均被测住：①不需登录放行 ②无 token 跳登录带 redirect ③有 token 拉 /me 成功放行
③b 拉 /me 失败清登录态跳登录 ④角色不符跳 403 ⑤角色放行。

### ⚠️ 依赖后端、暂不能端到端登录

`login()` → `POST /api/v1/identity/login`、`getCurrentUser()` → `GET /api/v1/identity/me`
**后端尚未实现**（identity 模块还空）。所以现在 `pnpm dev` 打开应用会：跳到登录页 → 输账号密码点登录 →
请求 404/网络错 → 拦截器弹 toast，进不去。这是**预期的前端先行状态**，等后端落地这两个接口即通。
前端逻辑本身已被单测覆盖（用 mock 验证成功路径），不依赖后端。

### 运行时自检（无需后端也能看的部分）

`pnpm dev` 后：
- 直接访问 `/knowledge` → 自动跳 `/login?redirect=/knowledge`（守卫②生效）。
- 访问不存在的路径如 `/nope` → 显示 404 页（BlankLayout 无壳）。
- 浏览器标签标题随页面变化（`afterEach` 设的 `xx · Hify`）。

### 故意搞错（确认守卫真在守门）

把 `guard.ts` 第②步"无 token 跳登录"改成直接 `return true`，`pnpm test`——
"需登录但无 token → 跳登录"用例应变红。能变红即守卫有效；验完改回。

---

## 地基 4：identity 登录闭环（建表迁移 V4 + 登录接口 + 首个 admin 引导）

对应改动：`server/src/main/resources/db/migration/V4__*.sql`（`sys_user` 表）、
`identity` 模块（`AuthService`、登录 Controller、`identity.service` 的 `AdminBootstrapRunner`）
（读 `hify.identity.bootstrap-admin.{username,password}`）、`application.yml`（新增该配置块）、
`deploy/.env.example`（新增 `HIFY_ADMIN_USERNAME`/`HIFY_ADMIN_PASSWORD`）。

### 自动化自检（首选）

```bash
mvn -f server/pom.xml test
```
- ✅ `BUILD SUCCESS`，且本轮新增的 identity 测试全过（含 `AuthServiceTest`、登录 Controller 测试），
  叠加既有的 `ModularityTests`、`LayerRulesTest` 等模块边界/分层校验同样全绿。
- ❌ 红灯：`BUILD FAILURE`，或任一测试 `Failures > 0`。看 `server/target/surefire-reports/*.txt` 定位。

### 运行时自检（手动冒烟，眼见为实）

1. 在 `deploy/.env`（从 `.env.example` 复制）里配好：
   ```
   HIFY_ADMIN_USERNAME=admin
   HIFY_ADMIN_PASSWORD=<一个强密码>
   ```
2. 启动应用 `mvn -f server/pom.xml spring-boot:run`，确认：
   - `sys_user` 表自动建好（`docker exec hify-postgres psql -U hify -d hify -c "\d sys_user"`）；
   - 日志或 DB 能看到首个 admin 账号被创建一次
     （`docker exec hify-postgres psql -U hify -d hify -c "select username,role from sys_user;"`）。
3. 拿这个 admin 账号登录拿 token：
   ```bash
   curl -i -X POST localhost:8080/api/v1/identity/login \
     -H "Content-Type: application/json" \
     -d '{"username":"admin","password":"<上面配的密码>"}'
   ```
   正确输出：`HTTP 200` + `Result.data` 里有 `token` 字段。
4. 证明令牌能通过 `JwtAuthenticationFilter` 验票。注意**不能**拿 `/api/v1/demo-items`（它在
   SecurityConfig 里是 `permitAll`，带不带令牌都 200，证明不了任何事），要拿一个**真受保护**的路径
   ——本轮还没有受保护的业务 GET 接口，所以用一个受保护命名空间下不存在的路径，看「无令牌 vs 有令牌」的对比：
   ```bash
   # 无令牌：被安全链拦下 → 401 + 业务码 10002
   curl -i localhost:8080/api/v1/identity/me
   # 带令牌：不再是 401（令牌已被接受，因无此接口而落到 404）→ 证明验票通过
   curl -i -H "Authorization: Bearer <上一步拿到的token>" localhost:8080/api/v1/identity/me
   ```
   正确输出：第一条 `HTTP 401`（`code 10002`），第二条 `HTTP 404`（**关键是不再 401**，说明令牌通过了验票）。
   > 等后续轮次有了受保护的业务 GET 接口（如 admin 用户列表），可改用它做「带令牌 → 200」的正向验证。
5. 错误凭据应返回 `HTTP 401` + `code 11001`；用一个被停用的账号登录应返回 `HTTP 403` + `code 11002`
   （本轮无管理接口，停用账号需手动改库验证）：
   ```bash
   # 停用 admin（验完记得改回 'enabled'）
   docker exec hify-postgres psql -U hify -d hify \
     -c "update sys_user set status='disabled' where username='admin';"
   ```
   再用 admin 登录应返回 `HTTP 403` + `code 11002`。

### 故意搞错（确认登录闭环真在守门）

把 `AuthService` 里密码校验那行临时改成永远通过，再用错误密码登录——应仍返回 401/11001 的检查会**变红**
（错误密码反而登录成功）。这证明密码校验不是摆设。验证完恢复该行。

> 📌 本轮**不含**：admin 用户管理接口、自助查询/改密、前端登录页接通、Testcontainers 连库测试——
> 这些留待后续轮次（前端地基 4 已实现 UI 与守卫，但依赖的两个接口本轮才落地，需重新跑一遍前端运行时自检确认能端到端登录）。

---

## 地基 5：admin 用户管理（`AdminUserService` 7 方法 + `AdminUserController` 7 接口）

对应改动：`identity` 模块新增 `AdminUserService`（create/list/enable/disable/resetPassword/changeRole/delete）、
`UserView` 响应投影、`IdentityError.CANNOT_REMOVE_LAST_ADMIN`(11003)、三个请求 DTO
（`CreateUserRequest`/`ResetPasswordRequest`/`ChangeRoleRequest`）、`AdminUserController`
（路由 `/api/v1/admin/identity/users`，由 `SecurityConfig` 既有的 `/api/v1/admin/**` → `hasRole("ADMIN")` 统一拦截）。
核心不变量：**统一护栏 `assertNotLastEnabledAdmin`**——停用 / 降级(admin→member) / 删除三处共用，保证系统里
至少保留一个启用的 admin 账号，命中即抛 11003。

### 自动化自检（首选）

```bash
mvn -f server/pom.xml test
```
- ✅ `BUILD SUCCESS`，`Tests run: 95, Failures: 0, Errors: 0`；本轮新增
  `AdminUserServiceTest`(17)、`AdminUserControllerTest`(10) 全过，叠加既有 `ModularityTests`(1)、
  `LayerRulesTest`(5) 同样全绿（确认新 controller/service/dto 放对层、无跨模块越界）。
- ❌ 红灯：`BUILD FAILURE`，或任一测试 `Failures > 0`。看 `server/target/surefire-reports/*.txt` 定位。

### 运行时自检（手动冒烟，眼见为实）

1. 启动应用 `mvn -f server/pom.xml spring-boot:run`，用 bootstrap admin 账号登录拿 token：
   ```bash
   curl -s -X POST localhost:8080/api/v1/identity/login \
     -H "Content-Type: application/json" \
     -d '{"username":"admin","password":"<.env 配的密码>"}' | tee /tmp/login.json
   TOKEN=$(jq -r .data.token /tmp/login.json)
   ```
2. 用 admin token 走通 7 接口（注意 id 在响应里是字符串）：
   ```bash
   # 创建
   curl -s -X POST localhost:8080/api/v1/admin/identity/users -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" -d '{"username":"bob","password":"rawpw1234","role":"member"}'
   # 列表
   curl -s localhost:8080/api/v1/admin/identity/users -H "Authorization: Bearer $TOKEN"
   # 停用/启用（把上一步返回的 id 代入 <ID>）
   curl -s -X POST localhost:8080/api/v1/admin/identity/users/<ID>/disable -H "Authorization: Bearer $TOKEN"
   curl -s -X POST localhost:8080/api/v1/admin/identity/users/<ID>/enable  -H "Authorization: Bearer $TOKEN"
   # 重置密码
   curl -s -X PUT localhost:8080/api/v1/admin/identity/users/<ID>/password -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" -d '{"password":"newpw5678"}'
   # 改角色
   curl -s -X PUT localhost:8080/api/v1/admin/identity/users/<ID>/role -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" -d '{"role":"admin"}'
   # 删除
   curl -s -X DELETE localhost:8080/api/v1/admin/identity/users/<ID> -H "Authorization: Bearer $TOKEN"
   ```
   正确输出：均 `HTTP 200`，且响应体里**不出现** `passwordHash` 字段。
3. 验权限分层：用 `bob`（member 角色）登录拿 token，再访问任意上述接口——期望 `HTTP 403` + `code 10004`。
   无令牌访问 `GET /api/v1/admin/identity/users`——期望 `HTTP 401` + `code 10002`。
4. 验校验：创建用户传空用户名/短密码/非法角色——期望 `HTTP 400` + `code 10001`，`data` 是字段错误数组。
5. 验重名冲突：再创建一个用户名为 `bob` 的账号（步骤 2 已建过）——期望 `HTTP 409` + `code 10006`。
   （并发同名极端情况由 `create` 兜底捕获 DB 唯一索引冲突 `DuplicateKeyException`，同样转 409/10006。）
6. 验「保留最后一个启用 admin」护栏（11003）。先确认当前只有一个启用 admin（bootstrap 账号），再试停用它：
   ```bash
   curl -s -X POST localhost:8080/api/v1/admin/identity/users/<admin的ID>/disable -H "Authorization: Bearer $TOKEN"
   ```
   期望 `HTTP 409` + `code 11003`（`IdentityError.CANNOT_REMOVE_LAST_ADMIN` 映射 `HttpStatus.CONFLICT`）。
   同理：把它降级成 member、或删除它，都应分别命中 11003。验证完不需要还原（本来就没改动成功）。

### 故意搞错（确认护栏真在守门）

把 `AdminUserService.assertNotLastEnabledAdmin` 方法体临时改成直接 `return;`（空实现），重跑
`mvn -f server/pom.xml -Dtest=AdminUserServiceTest test`——覆盖「停用/降级/删除最后一个启用 admin 应抛 11003」
的用例应**变红**（操作"成功"了，没有抛异常）。这证明三处共用的护栏不是摆设。验证完恢复该方法体。

> 📌 本轮**不含**：前端 admin 用户管理页面、Testcontainers 连库测试——
> 本轮服务层测试用 mock `SysUserMapper`（沿用既有惯例，连库测试统一推迟到 knowledge 模块手写 SQL 那轮）。

## Provider 后端 Task 1：ApiKeyCipher（2026-06-24）
- AES-256-GCM 加解密器就位，主密钥经 SHA-256 派生、随机 12B IV、128b tag。
- 测试：往返一致 / 同明文两次密文不同（随机IV）/ 换密钥解密失败，3 测全绿。
- 配置外化：application.yml `hify.provider.crypto.master-key` 引用 `${HIFY_PROVIDER_MASTER_KEY}`（dev 默认值）。
- ⚠️ deploy/.env.example 因目录写保护未能自动追加 HIFY_PROVIDER_MASTER_KEY，需手动补。

## Provider 后端 Task 2：数据层（2026-06-24）
- V5 建表 model_provider：text+check 枚举、boolean deleted、部分唯一索引 (name) where deleted=false。
- ProviderStatus 枚举、ModelProvider 实体（继承 BaseEntity）、ModelProviderMapper 就位。
- 经 ProviderServiceTest 编译+运行间接验证（实体/Mapper 可用）。

## Provider 后端 Task 3：ProviderService（2026-06-24）
- CRUD + 启停 + 重名预检/并发兜底 + 不存在 NOT_FOUND + 删除/启停幂等 + apiKey 留空保留，12 测全绿。
- 投影 ProviderResponse 不含密文/明文 key（编译期保证）。

## Provider 后端 Task 4：AdminProviderController（2026-06-24）
- 6 端点（list/create/update/delete/enable/disable）路由通；id 序列化为字符串、响应无密文。
- admin 200 / member 403-10004 / 无令牌 401-10002 / protocol 非法 400-10001 带字段数组，9 测全绿。

## Provider 后端 Task 5：全量回归（2026-06-24）
- mvn test 全绿：120 tests / 0 failures / 0 errors（25 类），含 Modulith/ArchUnit 边界校验无违规。
- Provider 后端第 1 轮（CRUD + Key 加密）完成。

## 前端 ProviderList 切真后端（2026-06-24）
- types: type(4值)→protocol(2值)、Provider 加 apiKeyTail；api: mock 换真实 request 6 端点（enable/disable/delete 返 void）。
- 页面: 协议标签「OpenAI 兼容/Anthropic」、新增 API Key 掩码列(••••尾巴)、submitForm try/catch 失败弹窗不关。
- TDD: 先改测试到红(3 失败)→实现→10 用例绿。全量 pnpm test 14 文件/69 测试绿，typecheck + lint 通过。

## ai_model 后端 B-Task1 数据层（2026-06-24）
- V6 建表 ai_model：FK→model_provider(id)、(provider_id,model_key) 部分唯一、text+check 枚举。
- ModelType / ProviderError(12001 EMBEDDING_NOT_SUPPORTED) / AiModel 实体 / AiModelMapper 就位。

## ai_model 后端 B-Task2 AiModelService（2026-06-24）
- CRUD + 启停 + embedding 协议守卫(anthropic+embedding→12001) + 重名/不存在/幂等，14 测全绿。
- 修：BaseMapper insert/updateById 重载致 any() 歧义，verify 改 any(AiModel.class)。

## ai_model 后端 B-Task3 AdminModelController（2026-06-24）
- 6 端点混合路由（列表/创建挂供应商下、单条走顶级）通；admin/member/@Valid 校验，8 测全绿。

## ai_model 后端 B-Task4 删供应商守卫（2026-06-24）
- ProviderService 注入 AiModelMapper；delete 收紧为"有未删模型则拒删(10006)"。原 12 + 守卫 1 = 13 测全绿。

## ai_model 后端 B-Task5 全量回归（2026-06-24）
- mvn test 全绿：143 tests / 0 failures / 0 errors（27 类），含 Modulith/ArchUnit 无违规。
- 注：测试 mock 不连库；V6 迁移会在下次启动 server（跑 Postman）时由 Flyway 自动 apply。
- ai_model 模型管理后端（B 轮）完成。

## 模型管理前端 Task1 类型与 API 层（2026-06-24）
- types/model.ts（ModelType/AiModel/ModelForm，id/providerId 为 string）+ api/admin/model.ts 封装 6 端点。
- URL 对齐后端（列表/创建挂 providers/{id}/models、单条走 models/{id}）；TDD 6 测先红后绿。

## 模型管理前端 Task2 路由+入口（2026-06-24）
- ProviderList 操作列加「管理模型」按钮（router.push /admin/provider/:id），列宽 240→320。
- 注册 ProviderDetail 路由（roles:['admin']，无 menu 不进侧边栏）；ProviderList 11 测、menu 9 测全绿。

## 模型管理前端 Task3 详情页（2026-06-24）
- ProviderDetail.vue：listProviders 按 id 找供应商（404 兜底）+ listModels 渲染表；新增/编辑/删除/启停对话框，anthropic 下 embedding 选项禁用，编辑态 type 只读。
- TDD 13 测先红后绿；全量 16 文件/89 测绿，typecheck + lint 通过。
- 模型管理前端 UI 完成。

## 模型管理前端 修复：编辑态 type 可改 bug（2026-06-24）
- 根因：embedding radio 的 :disabled="embeddingDisabled"(openai 下为 false) 覆盖了 radio-group 的 :disabled，编辑态仍可切 chat→embedding；update 只传 name+modelKey 故"提示成功但类型未变"。
- 修：编辑态不渲染 radio 组，改 el-tag 只读展示；radio（含 embedding 置灰）只在新增态出现。
- 加回归测试（编辑态无 form-type、显示 form-type-readonly）；全量 16 文件/90 测绿。

## app 模块 Task1 jsonb 配置载体（2026-06-24）
- AppConfig（record，systemPrompt）+ AppConfigTypeHandler（写出 PGobject(jsonb)、读入反序列化，null/空白兜底 new AppConfig(null)）。
- 顺带修：pom.xml 里 postgresql 驱动从 `<scope>runtime</scope>` 改为编译期可见——TypeHandler 在 main 代码里直接 import PGobject，runtime scope 编译不过。
- 自证：handler 单测 3 绿（写出序列化、读入反序列化、读入空值兜底）；mvn test 全量 146 测/0 失败/0 错误（含原 143 + 新增 3）。
- 遗留：jsonb 落库正确性待 Task 9（建 app 实体/表）端到端走查，本任务仅验证 TypeHandler 单元行为，未连库。

## app 模块 Task2 建表迁移+常量+实体+Mapper（2026-06-24）
- V7 建表 app：text+check 枚举(type/status)、jsonb config、跨模块 model_id/owner_id 只存 id 不建外键、部分唯一索引 (name) where deleted=false。
- AppType(chat/workflow) / AppStatus(enabled/disabled) / AppError(16001 APP_TYPE_NOT_SUPPORTED, 400) / App 实体（继承 BaseEntity，config 用 @TableField(typeHandler=AppConfigTypeHandler.class) + 类上 @TableName(autoResultMap=true)）/ AppMapper 就位。
- TDD：AppEnumTest 先红（AppType/AppStatus/AppError 未定义，编译失败）→ 写实现后绿。
- mvn -Dtest=AppEnumTest,ModularityTests,LayerRulesTest test：8 测全绿；mvn test 全量：148 tests/0 failures/0 errors（含原 146 + 新增 2），含 Modulith/ArchUnit 模块边界与分层校验无违规。
- 遗留：建表 SQL 与 App 实体的真实数据库读写（含 jsonb config 端到端）未连库验证，留待后续轮次（Service 层落地或 Testcontainers）一并走查。

## app 模块 Task3 AppService.create + DTO（2026-06-24）
- CreateAppRequest/AppResponse（record DTO）+ AppService.create（type 非 chat→16001；owner=current.userId()；status 默认 enabled；config 缺省兜底 new AppConfig(null)；插入撞唯一索引 catch DuplicateKeyException→CONFLICT，不先查后插）+ 包级 toResponse 供后续任务复用。
- service 方法签名收 CurrentUser 参数，不直接读安全上下文，延续 AdminUserService 既有做法，便于单测。
- TDD：AppServiceTest 4 例先红（dto/AppService 未定义，编译失败）→ 写实现后绿。
- 踩坑：brief 测试原文 `verify(mapper, never()).insert(any())` 中 bare `any()` 与 BaseMapper 的 `insert(T)`/`insert(Collection<T>)` 两个重载产生编译期歧义（"reference to insert is ambiguous"）；改成 `any(App.class)`（与 AdminBootstrapRunnerTest/AiModelServiceTest 既有写法一致）后消歧。
- mvn -Dtest=AppServiceTest test：4 测全绿；mvn test 全量：152 tests/0 failures/0 errors（含原 148 + 新增 4），含 Modulith/ArchUnit 校验无违规。

## app 模块 Task4 AppService 读取（get + page）（2026-06-24）
- AppService 追加 get(id)（不存在→NOT_FOUND）、page(keyword, type, page, size)（page*size>10000→PARAM_INVALID；LambdaQueryWrapper：keyword→name like，type→等值，orderByDesc(id)；@TableLogic 自动加 deleted=false；不按 owner 过滤，团队全可见）→ 映射成 PageResult<AppResponse>。两个方法均只读，不加 @Transactional。
- TDD：AppServiceTest 追加 3 例先红（get/page 未定义，编译失败，Tests run: 7/Errors: 7）→ 写实现后绿。
- mvn -Dtest=AppServiceTest test：7 测全绿；mvn test 全量：155 tests/0 failures/0 errors（含原 152 + 新增 3），含 Modulith/ArchUnit 校验无违规。

## app 模块 Task5 AppService 改/删/启停 + 团队共享权限判定（2026-06-24）
- 新增 UpdateAppRequest（record，无 type，type 不可改）；AppService 追加 update/delete/enable/disable + 私有 assertCanModify(App, CurrentUser)（非 owner 且非 admin→FORBIDDEN）/ loadOrThrow(Long)（不存在→NOT_FOUND）。
- 团队共享制落地：改/删/启停统一先 loadOrThrow 再 assertCanModify；delete 幂等——selectById 为 null 直接 return（不报错不查权限，因无对象可判）；update 改名撞唯一索引 catch DuplicateKeyException→CONFLICT。
- TDD：AppServiceTest 追加 7 例先红（UpdateAppRequest/update/delete/disable 未定义，编译失败）→ 写实现后绿。
- 踩坑：brief 测试原文 `verify(mapper, never()).updateById(any())` / `deleteById(any())` 中 bare `any()` 与 BaseMapper 的单体/Collection 重载产生编译期歧义；沿用 Task3 既有约定改成 `any(App.class)` / `any(Long.class)` 消歧。
- mvn -Dtest=AppServiceTest test：14 测全绿（原 brief 预估"共 15 例"，实际累计 14——本轮净增 7 例，与原有 7 例相加为 14，预估数与实际不一致，已用实测值为准）；mvn test 全量：162 tests/0 failures/0 errors（含原 155 + 新增 7），含 Modulith/ArchUnit 模块边界与分层校验无违规。

## app 模块 Task5 评审补强：enable/disable 测试覆盖缺口（2026-06-24）
- 评审发现：enable() 此前从未被任何测试调用——若 enable/disable 把 ENABLED/DISABLED 写反，现有测试抓不到；disable() 的「他人非 admin → FORBIDDEN」分支未覆盖。生产代码已正确，仅补测试。
- AppServiceTest 追加 2 例：`启用_owner放行_写enabled`（ArgumentCaptor 抓 updateById 实体，断言 status="enabled"，专门防 enable/disable typo 写反）、`停用_他人非admin_拒绝FORBIDDEN`（断言 CommonError.FORBIDDEN 且 updateById 未被调用）。
- mvn -Dtest=AppServiceTest test：16 测全绿（原 14 + 新增 2）；mvn test 全量：164 tests/0 failures/0 errors（含原 162 + 新增 2），含 Modulith/ArchUnit 校验无违规。

## app 模块 Task6 AppController（7 端点，后端最后一个任务，2026-06-24）
- 新增 AppController（成员路由 `/api/v1/app/apps`）：list/get/create/update/delete/enable/disable，纯协议层——@Valid 校验 → CurrentUserHolder.current() 取当前用户 → 调 AppService → 包 Result；无业务逻辑、无 try-catch、无 @Transactional；list/get 不取当前用户（service 不按 owner 过滤，团队全可见）。
- 分页参数 `page`/`size` 用 `@RequestParam(defaultValue=...) int`，不加 @Min/@Max/@Validated（避免 ConstraintViolationException 落兜底 10000/500 而非 10001/400）。
- TDD：AppControllerTest 4 例（@WebMvcTest + 导入 SecurityConfig/JwtService 等 + 成员 JWT）先红（AppController 未定义，编译失败）→ 写实现后绿，覆盖：成员可访问列表（PageResult，Long/long 全局序列化为 string）、未登录 401、创建空名 400/10001、创建成功返回完整资源含 config.systemPrompt。
- mvn -Dtest=AppControllerTest test：4 测全绿；mvn test 全量：168 tests/0 failures/0 errors（含原 164 + 新增 4），含 Modulith/ArchUnit 模块边界与分层校验无违规。
- app 模块第一轮后端（Task1-6）全部完成：jsonb 配置载体 → 建表/常量/实体/Mapper → create → get/page → update/delete/enable/disable+团队共享权限 → Controller。

## app 模块 Task7 前端类型与 API 层（2026-06-24）
- `web/src/types/app.ts`：AppType/AppStatus/AppConfig/App（id/modelId/ownerId 为 string）/AppForm（创建编辑共用，不含 type/modelId）/PageResult<T>（total/page/size 均 string，对齐后端 Long 全局序列化）。
- `web/src/api/app.ts`：listApps/getApp/createApp/updateApp/deleteApp/enableApp/disableApp 7 个函数，封 `/app/apps` 成员路由（放 `api/` 根，不进 `admin/`）；createApp 在 body 里注入固定 `type:'chat'`。
- TDD：`web/src/api/__tests__/app.spec.ts` 6 例（mock `@/api/request`，断言 url/params/body）先红（`@/api/app` 解析失败）→ 写 types+api 后绿。
- `pnpm vitest run src/api/__tests__/app.spec.ts`：6/6 通过；`pnpm test` 全量 17 文件/96 测全绿；`pnpm typecheck`、`pnpm lint` 均无错误。
- 前端 app 模块类型与 API 层完成，为后续页面（列表/表单组件）任务铺路。

## app 模块 Task8 前端应用列表页 AppList.vue（本轮最后一个任务，2026-06-24）
- 替换占位 `web/src/views/app/AppList.vue`：服务端分页（`listApps({keyword,page,size})`，`total` 用 `Number()` 转后端 string 给 el-pagination；翻页/搜索重拉，搜索回第 1 页）+ `canModify(app)=userStore.isAdmin || app.ownerId===userStore.user?.id` 门控操作列（他人应用显示 `—`，无按钮）+ 创建/编辑共用弹窗（名称必填≤50/描述≤200/系统提示词 textarea 可选；类型固定「对话应用」标签展示，编辑不可改）。范式照 `ProviderList.vue`/`UserList.vue`：PageHeader+ContentCard+el-table+el-dialog、data-test 锚点、confirmDanger 二次确认、happy-dom 下 el-form 空必填误判兜底（手动再判一遍 name 非空与长度）。
- TDD：`web/src/views/app/__tests__/AppList.spec.ts` 4 例（挂载渲染、canModify 门控、删除调用、空名不提交）先红（占位组件无表格/按钮/data-test，4 测全 FAIL）→ 写实现后绿。
- `pnpm vitest run src/views/app/__tests__/AppList.spec.ts`：4/4 通过；`pnpm test` 全量 18 文件/100 测全绿（含原 17/96 + 新增 1 文件/4 测）；`pnpm build`（含 `vue-tsc --noEmit` 类型检查 + vite build）无错误；`pnpm lint` 无问题。
- app 模块第一轮前端（Task7-8）全部完成：类型与 API 层 → 应用列表页（分页+权限门控+创建编辑弹窗）。后续轮次（模型选择器、应用详情/对话页等）留待评估。

## app 模块第一轮全分支终审修复 #1-3（2026-06-24）
- #1（行为加固）：`AppService.page()` 页深护栏 `(long) page * size > 10_000` 在 page/size 为负数时乘积变负，绕过护栏；扩下界为 `page < 1 || size < 1 || (long) page * size > 10_000`。未改用 `@Min/@Max/@Validated`——全局异常处理器无 `ConstraintViolationException` 处理，会落兜底 10000/500 把 400 变 500，故仍在 service 层判断。`AppServiceTest` 追加 `分页_负数页_抛PARAM_INVALID`（`service.page(null,null,-1,20)` → `CommonError.PARAM_INVALID`）。
- #2（卫生）：`AppConfigTypeHandlerTest` 删除未使用的 `import static org.mockito.ArgumentMatchers.anyInt;`（文件内确认无 `anyInt(` 调用）。
- #3（卫生）：`AppServiceTest` 的 `创建_config缺省兜底为空配置` 用例 `assertEquals(null, ...)` 改 `assertNull(...)`，补 `import static org.junit.jupiter.api.Assertions.assertNull;`。
- `mvn -Dtest=AppServiceTest,AppConfigTypeHandlerTest test`：AppServiceTest 17 例全绿（16+1）、AppConfigTypeHandlerTest 3 例全绿；`mvn test` 全量：169 tests/0 failures/0 errors（原 168 + 新增 1），含 Modulith/ArchUnit 模块边界校验无违规。

## provider C1 · Task 1：模型可用性只读查询（2026-06-25）
- 对应改动：`provider/api/dto/ModelView.java`（首个对外 DTO）、`provider/service/ModelQueryService.java`（只读，无 @Transactional）。
- 做了什么：`findUsableChatModel(id)` 与 `listUsableChatModels(type)`，「可用」= 模型 enabled + type=chat + 所属供应商 enabled。连带供应商判定用两次 MyBatis-Plus wrapper 查询（selectById/selectList + selectBatchIds）拼装，不写手写 SQL。
- 怎么自证：`mvn -Dtest=ModelQueryServiceTest test` → `Tests run: 9, Failures: 0`（见 target/surefire-reports）。覆盖：可用正常返回 ModelView、模型不存在/停用/非chat（且非chat不查供应商，verify never）/供应商停用均 empty；列表过滤掉停用供应商的模型、无可用回空列表非null、type 为空兜底 chat。
- 反向验证：把「供应商停用」用例的断言改成期望 present 会立即红——证明连带校验真的生效。
- 已知遗留：getChatClient/韧性留 C2；embedding 列举留 knowledge 轮（type 参数已预留）。

## provider C1 · Task 2：首个对外门面 ProviderFacade（2026-06-25）
- 对应改动：`provider/api/ProviderFacade.java`（接口，provider 首个 Facade）、`provider/service/ProviderFacadeImpl.java`（薄委托 ModelQueryService）。
- 怎么自证：`mvn -Dtest=ProviderFacadeImplTest,ModularityTests,LayerRulesTest test` → 8 tests/0 failures（ProviderFacadeImplTest 2 + ModularityTests 1 + LayerRulesTest 5）。Modulith/ArchUnit 绿 = 新增 Facade(api) 与 ModelView(api/dto) 不破坏模块边界。
- 反向验证：把 ProviderFacade 放进非 api 包，ModularityTests/LayerRulesTest 会报对外接口未在 api 暴露——证明边界守门有效。
- 已知遗留：getChatClient/getEmbeddingModel 方法留 C2。

## provider C1 · Task 3：成员侧模型列表端点（2026-06-25）
- 对应改动：`provider/controller/ModelQueryController.java`（成员族 GET /api/v1/provider/models?type=chat）。
- 怎么自证：`mvn -Dtest=ModelQueryControllerTest test` → `Tests run: 2`。覆盖：成员 token 可访问、返回 Result 信封、id 序列化为 string、providerName 存在、type 默认 chat 透传 service；未登录 → 401。
- 安全配置核对：SecurityConfig admin 匹配器是 `/api/v1/admin/**`（hasRole ADMIN），`/api/v1/provider/**` 落 `anyRequest().authenticated()` → 成员可访问，无需改 SecurityConfig。
- 反向验证：未登录用例预期 401——若把路由误挂到 admin 段或漏鉴权会变 403/200 而红。

## provider C1 · Task 4：app 接 ProviderFacade 校验 model_id（2026-06-25）
- 对应改动：`app/constant/AppError.java`（+16002 MODEL_NOT_USABLE）、`app/service/AppService.java`（注入 ProviderFacade，create/update 调 assertModelUsableIfPresent）。app 首次真正 import `provider::api`（白名单早已允许）。
- 行为：modelId 选填——非空才经 ProviderFacade.findUsableChatModel 校验，空则 empty 抛 16002；null 直接放行。
- 怎么自证：`mvn -Dtest=AppServiceTest test` → `Tests run: 21`（原 17 + 新增 4：创建/更新各「模型不可用→16002」「modelId 为 null 不校验放行」）；`mvn test` 全量 186/0/0，Modulith+ArchUnit 绿（跨模块 import 合规）。
- 反向验证：把 assertModelUsableIfPresent 的 null 判断去掉，「modelId 为 null 不校验」用例会因 facade 被调用（verify never 失败）而红。
- 已知遗留：编辑既有 app 时若所选模型后被禁用，须重选/清空才能存（spec §3.2 已记，C1 接受）。

## provider C1 · Task 5：前端成员侧模型 api（2026-06-25）
- 对应改动：`web/src/types/model.ts`（+ModelOption）、`web/src/api/provider.ts`（listChatModels）。
- 怎么自证：`pnpm vitest run src/api/__tests__/provider.spec.ts` → 1 passed；断言 `request.get` 以 `('/provider/models', { params: { type: 'chat' } })` 调用。

## provider C1 · Task 6：app 弹窗模型选择器（2026-06-25）
- 对应改动：`web/src/types/app.ts`（AppForm +modelId）、`web/src/views/app/AppList.vue`（弹窗 el-select + 打开弹窗拉 listChatModels + 创建/编辑回填）。
- 怎么自证：`pnpm vitest run src/views/app/__tests__/AppList.spec.ts` → 8 passed（原 4 + 新增 4：打开弹窗拉模型/选中→createApp 带 modelId/不选→null/编辑回填）；`pnpm test` 全量 19 文件 105 测全绿；`pnpm build`（vue-tsc + vite）通过；`pnpm lint` 无问题。
- 反向验证：把 openCreate 里 `form.modelId = null` 去掉，「不选→modelId 为 null」用例会因残留上次值而红。
- 已知遗留：模型后被禁用时编辑须重选/清空才能存（spec §3.2）。

## provider C1.1：列表/详情回显模型名 + 失效模型不裸露 id（2026-06-25）
- 背景：浏览器验证发现两点 UX——列表不显示所选模型名；模型供应商被停用后编辑弹窗裸露 modelId 数字。根因同一个：前端只有 modelId 没有名字。
- 后端：ProviderFacade + ModelQueryService 加 `getModelNames(ids)`（展示用，不管启停都返回名字，与「可用」过滤区分）；AppResponse 加 `modelName`（只增不改契约）；AppService 单条 modelNameOf + 分页批量解析。
- 前端：App 类型加 modelName；列表加「模型」列（无则「未配置」）；编辑弹窗用 selectOptions 计算属性，所选模型不在可用列表时注入「名字（已停用）」禁用选项，不再裸露数字。
- 怎么自证：`mvn test` 全量 192/0/0（+6：getModelNames 3 + facade 透传 1 + create/page 回显 2）；`pnpm test` 107 全绿（+2）、`pnpm build`、`pnpm lint` 通过。
- 坑记录：`Map.of()` 不可变 map 不允许 get(null)，分页里 modelId 为 null 的行要先判空再取名，否则 NPE（已修，AppService.page）。
- 反向验证：把「失效注入」用例的 listChatModels 改成返回该模型，注入分支不触发、断言禁用选项不存在而红。

## provider C1.2：列表模型列标「（已停用）」（2026-06-25）
- 背景：列表模型列与编辑弹窗口径一致——停用模型在列表也标注，owner 不点进去就知道模型坏了。
- 后端：ProviderFacade + ModelQueryService 加 `filterUsableChatModelIds(ids)`（批量「可用」过滤，复用 enabled+chat+供应商enabled 定义）；AppResponse 加 `modelUsable` 布尔；AppService 单条 modelUsableOf（复用 findUsableChatModel）+ 分页批量 filterUsableChatModelIds，null 模型 modelUsable=false。
- 前端：App 类型加 modelUsable；列表模型列在 modelName 后按 !modelUsable 追加灰色「（已停用）」（同一行渲染消除空格间隙，保证连续文本）。
- 怎么自证：`mvn test` 全量 196/0/0（+4：filterUsableChatModelIds 2 + facade 透传 1 + 分页 modelUsable 1）；`pnpm test` 109 全绿（+2）、build、lint 通过。
- 反向验证：把列表用例的 WITH_MODEL.modelUsable 改 true，「名字后加（已停用）」用例会因后缀不出现而红。

## conversation ⑦ 会话管理 Task1：删除会话（软删+级联+幂等，2026-07-01）
- 对应改动：`ConversationStore.deleteConversation`（@Transactional，事务收口）、`ConversationService.deleteConversation`（薄委托、无事务）、`ConversationController` 新增 `DELETE /api/v1/conversation/conversations/{id}`（成员族，返 `Result<Void>` data:null）。分支 `feat/conversation-management`。
- 做了什么：按 `user_id` 作用域软删会话（`conversationMapper.delete(wrapper.eq(id).eq(userId))`，@TableLogic 转 UPDATE deleted=true），命中(rows>0)才级联软删该会话消息。0 行命中（非本人/已删）静默成功——满足 DELETE 幂等（api-standards §2.2）且不泄露存在性。权限仅本人（会话族口径），与应用的 owner+admin 不同。
- TDD：先写 3 类失败测试（StoreTest 2：命中级联/未命中不级联；ServiceTest 1：委托传当前用户；ControllerTest 2：200+dataNull/未登录401）→ 跑出「红」= `deleteConversation` 方法未定义（功能缺失，非拼写）→ 写实现 → 转「绿」。
- 怎么自证：`mvn test -Dtest=ConversationStoreTest,ConversationServiceTest,ConversationControllerTest` → `Tests run: 35, Failures: 0, Errors: 0`（Store 11 + Service 15 + Controller 9）。判定直接读 Tests run/Failures/Errors 行，不 grep BUILD SUCCESS。
- 反向验证：`deleteConversation_未命中_不级联` 用例 stub delete→0 并断言 `messageMapper.delete` never——若实现漏掉 `if (rows>0)` 无脑级联，此用例立刻红，守住幂等+级联逻辑。
- 无表结构变更、无新 Flyway、无新错误码（17xxx 不动）。下一步 Task2 重命名会话。

## conversation ⑦ 会话管理 Task2：重命名会话（动作子资源 POST /rename，2026-07-01）
- 对应改动：`RenameConversationRequest`（新 DTO，`@NotBlank @Size(max=100)`）、`ConversationStore.renameConversation`（@Transactional，assertOwned→改 title.strip）、`ConversationService.renameConversation`（薄委托）、`ConversationController` 新增 `POST /api/v1/conversation/conversations/{id}/rename`。
- 做了什么：重命名用**动作子资源 POST**（非 PUT 全量——会话唯一可改字段就是标题，PUT「未传置空」语义危险）。权限口径与删除**不同**：改名要给用户明确反馈，改他人/不存在会话经 `assertOwned` 抛 404（复用 CommonError.NOT_FOUND），非幂等静默。空标题经 @NotBlank → 10001/400。
- TDD：先写失败测试（StoreTest 2：owner改名strip/他人404不更新；ServiceTest 1：委托；ControllerTest 2：200/空标题400）→ 从 server 目录跑出真「红」= `renameConversation` 未定义 → 写实现 → 绿。
- 踩坑：`verify(...).updateById(any())` 编译歧义（MyBatis-Plus updateById 有 `(T)` 与 `(Collection<T>)` 两重载），改 `any(Conversation.class)` 消歧义——先跑编译才暴露。另注意 mvn 必须在 `server/` 目录下跑，切到仓库根会报「no POM」的假失败。
- 怎么自证：`mvn test` 全量 `Tests run: 287, Failures: 0, Errors: 0`（含 ModularityTests/LayerRulesTest；新 DTO 不 import entity，模块边界不破）。
- 反向验证：`renameConversation_他人会话_404_不更新` 断言 updateById never——若实现漏掉 assertOwned 直接更新，此用例因 updateById 被调而红，守住「仅本人」权限。
- 无表结构变更、无新 Flyway、无新错误码。后端两端点（删除+重命名）完成，下一步转前端 Task3（api/store 接线）。

## conversation ⑦ 会话管理 Task3：前端 api/store 接线删除与重命名（2026-07-01）
- 对应改动：`api/conversation.ts`（+renameConversation POST、+deleteConversation DELETE）、`stores/conversation.ts`（+renameConversation 本地回显 title、+deleteConversation 移除列表项且删当前会话回空白态）。
- 做了什么：store action 与 api 函数同名，import 用别名（`apiRenameConversation`/`apiDeleteConversation`）避免遮蔽。重命名本地回显（找到列表项改 title，不重拉整表）；删除从 conversations 过滤掉，若删的是 currentId 则调 newConversation() 回空白态。
- TDD：先写失败测试（api 2：断言 request.post/delete 的 url+body；store 2：本地回显/移除+删当前回空白）→ 从 web 目录跑出真「红」= `is not a function`（4 failed）→ 写实现 → 绿。
- 怎么自证：`pnpm vitest run src/api/__tests__/conversation.spec.ts src/stores/__tests__/conversation.spec.ts` → `Tests 21 passed`；`pnpm typecheck` 无错。
- 反向验证：`deleteConversation 删当前会话回空白态` 用例断言 currentId=null、messages=[]——若实现漏掉 `if (id === currentId.value) newConversation()`，此用例因 currentId 仍为 '1' 而红。
- 下一步 Task4：侧边栏会话操作下拉（重命名/删除 UI）。

## conversation ⑦ 会话管理 Task4：侧边栏会话操作（重命名/删除，2026-07-01）
- 对应改动：`ConversationSidebar.vue`（会话条目加两个 hover 显现的行内图标 EditPen/Delete；emits +rename/+delete；onRename 用 ElMessageBox.prompt 取新标题、onDelete 用 ElMessageBox.confirm 二次确认，向上 emit）。
- 设计取舍：原计划 el-dropdown「更多」下拉，改为**行内 hover 图标**——el-dropdown 菜单 teleport 到 body 且需点开才渲染，测试难稳定抓取；只有 2 个操作，行内图标更简洁也更好测。图标默认 opacity:0，条目 hover 或为当前会话时显现。操作图标用 `@click.stop` 防止冒泡触发选中会话。
- TDD：先写失败测试（点重命名→prompt→emit rename 带新标题；点删除→confirm→emit delete；点图标不触发 select）→ 跑出真「红」= 3 failed（图标不存在）→ 写实现 → 绿。测试用 `vi.spyOn(ElMessageBox, 'prompt'/'confirm')` 桩住弹窗。
- 怎么自证：`pnpm vitest run src/views/conversation/__tests__/ConversationSidebar.spec.ts` → `Tests 11 passed`（原 8 + 新 3）；`pnpm typecheck` 无错。
- 反向验证：「点操作图标不触发 select」用例——若漏掉 `@click.stop`，点重命名会同时冒泡触发 `emit('select')`，该用例因 select 被 emit 而红，守住 stop 冒泡。
- 下一步 Task5：ChatView 气泡复制/编辑 + 免责提示 + 接线 sidebar 的 rename/delete 到 store。

## conversation ⑦ 会话管理 Task5：ChatView 气泡复制/编辑 + 免责提示 + 接线（2026-07-01）
- 对应改动：`ChatView.vue`——气泡改「内容+操作区」结构：用户气泡 hover 出复制(DocumentCopy)+编辑(EditPen，tooltip「重新编辑后发送」)，AI 气泡 hover 出复制；`canCopy(m,i)` 判定 AI 正在流式的最后一条不显示复制（sending && i===last）；`copyMsg` 走 navigator.clipboard + ElMessage 已复制；`editMsg` 轻量B 回填 input.value（不新增消息）；输入框下方加 `data-test="ai-disclaimer"` 免责提示；sidebar 接 `@rename→store.renameConversation`、`@delete→store.deleteConversation`（删当前会话后清 URL 的 ?c=）。
- TDD：先写 6 失败测试（复制用户/AI流式隐藏done显示/编辑回填不新增/免责文案/delete接线/rename接线）→ 真「红」6 failed → 实现 → 绿。接线测试用 `wrapper.findComponent(ConversationSidebar).vm.$emit(...)` + `vi.spyOn(store, ...)`，直接验证 ChatView 对子组件事件的响应。
- 踩坑：happy-dom 的 `navigator.clipboard` 是只读 getter，`Object.assign` 设不进，改 `Object.defineProperty(navigator,'clipboard',{value,configurable:true})`——测试自身问题，实现无误。
- 怎么自证：`pnpm vitest run src/views/conversation/__tests__/ChatView.spec.ts` → `Tests 14 passed`（原 8 + 新 6）；`pnpm typecheck` 无错。
- 反向验证：「AI 气泡流式中不显示复制、结束后显示」——若 canCopy 漏掉 sending 判定，流式中就会渲染复制图标，该用例因 exists()===true 而红。
- 下一步 Task6：对话入口（ChatHome 应用选择页 + 菜单 + 默认落地页 /chat）。

## conversation ⑦ 会话管理 Task6：对话入口（ChatHome + 菜单 + 默认落地页，2026-07-01）
- 对应改动：新增 `views/conversation/ChatHome.vue`（应用选择页，复用 `listApps` 拉应用、前端过滤 status==='enabled' 渲染卡片、点卡片 router.push `/apps/{id}/chat`、modelUsable=false 置灰不跳、空态 el-empty 引导去应用管理）；`router/index.ts`（新增 `/chat` 路由 menu+icon ChatDotRound，`/` 重定向由 `/knowledge` 改 `/chat`，对话菜单置于首位）；`DefaultLayout.vue`（iconMap 补 ChatDotRound——字符串名→组件映射，不补则菜单无图标）。
- 修复目标：原「聊天藏在应用管理→试聊」层级太深；现顶部「对话」为最高频入口，登录默认落此页。试聊按钮保留作快捷入口（Task7），两路同终点 /apps/:appId/chat。
- TDD：先写 ChatHome.spec 4 失败测试（只渲染已启用/点卡片 push/模型不可用不跳/空态）→ 真「红」= 组件不存在 → 实现 → 绿。
- 不破坏既有测试：menu.spec/guard.spec/DefaultLayout.spec 均用自造路由 fixture（非真实路由表），改真实重定向不影响；已连跑 router+layout 测试全绿。
- 怎么自证：`pnpm vitest run ChatHome.spec + src/router/__tests__ + src/layouts/__tests__` → `5 files / 24 passed`；`pnpm typecheck` 无错。
- 反向验证：「模型不可用的卡片点击不跳转」——若 open() 漏掉 `if (!a.modelUsable) return`，push 会被调用，该用例因 `push` 被调而红。
- 下一步 Task7：AppList 试聊按钮改实心蓝底 small、与其它按钮同排（全员可见）。

## conversation ⑦ 会话管理 Task7：AppList 试聊按钮改样式（2026-07-01）
- 对应改动：`AppList.vue` 操作列重构——试聊按钮去 `link` → `size="small" type="primary"` 实心蓝底；移入与其它三按钮同一个 `app-list__ops` flex 容器（已 display:flex + gap），试聊恒在最前，其余三（启用停用/编辑/删除）用 `<template v-if="canModify">` 门控；删除原 `<span v-else>—</span>`（无测试依赖、无样式定义）。
- 语义不变：试聊对全体成员可见（团队共享），编辑/删除仅 owner+admin。data-test 锚点（chat-/edit-/delete- 等）全保留。
- 无需新测试：既有 AppList.spec 已覆盖试聊三态（chat-4 跳转 /apps/4/chat、chat-3 模型不可用 disabled、chat-5 非 owner 可见可点且无 edit-5）。改样式后这些断言仍绿。
- 怎么自证：`pnpm vitest run src/views/app/__tests__/AppList.spec.ts` → `Tests 15 passed`；`pnpm typecheck` 无错；`pnpm lint` 通过。
- 反向验证：既有「非 owner 也可见可点」用例——若误把试聊挪进 canModify 门控，chat-5 不存在、该用例点击时找不到元素而红，守住「试聊全员可见」。
- 下一步 Task8：全量回归（后端 mvn test + 前端 pnpm test/typecheck/build）+ 手验清单。

## conversation ⑦ 会话管理 Task8：全量回归（2026-07-01）
- 后端全量：`cd server && mvn test` → `Tests run: 287, Failures: 0, Errors: 0`（含 ModularityTests/LayerRulesTest 模块边界）。
- 前端全量：`cd web && pnpm test` → `25 files / 167 passed`；`pnpm typecheck` 无错；`pnpm build`（vue-tsc + vite）成功（chunk 体积警告为既有，非本轮引入）；`pnpm lint` 通过。
- 本轮（⑦）7 个功能任务全部完成，分支 `feat/conversation-management`，逐任务提交：Task1 删除会话 / Task2 重命名会话 / Task3 前端 api-store 接线 / Task4 侧边栏操作 / Task5 气泡复制-编辑-免责+接线 / Task6 对话入口 ChatHome+菜单+默认落地 / Task7 试聊按钮样式。
- 待人工手验（需登录跑真环境）：默认落对话页→选应用进聊天；会话重命名/删除即时生效且刷新持久；用户气泡复制/编辑（编辑回填输入框发新消息）；AI 回答完成后左下角复制；输入框下方免责提示；试聊实心蓝底同排、他人应用可试聊但无编辑删除。
- 本轮不做（留后）：真编辑重新生成、会话列表分页/查看全部、LLM 自动标题、知识库(RAG)、Agent 工具调用、对外 API。

## conversation ⑦ 会话管理 UI 调整（按用户反馈，2026-07-01）
- 三处调整（TDD 各自先红后绿）：
  1. **用户消息行内编辑**：`ChatView.vue` 编辑不再回填主输入框，而是在原消息上出现行内编辑框（预填原文）+ 发送/取消；发送走抽出的 `deliver(text)`（与主输入框共用），在底部生成一条新消息，原消息不变。新增 `editingId/editingText` 态 + `startEdit/cancelEdit/submitEdit`。
  2. **AI 复制移到气泡外**：消息按 `chat__row`（flex 列，按角色 align-self）重构；AI 复制按钮移出气泡、在气泡外左下角（row--assistant 的 align-items:flex-start 靠左），回答完成后显现（canCopy 不变）；用户复制/编辑仍在气泡内 hover。
  3. **侧边栏 3 点下拉**：`ConversationSidebar.vue` 两个 hover 图标改为单个 MoreFilled（3 点）+ el-dropdown 菜单（重命名/删除），`@command` 分发到 onRename/onDelete；`sidebar__item` 加 `align-items:center` 让 3 点与标题文字对齐；`@click.stop` 防冒泡选中。
- 踩坑：① el-input 把 `data-test` 透传到内部 `<textarea>`，故行内编辑框需外包一层 `<div :data-test>`（同主输入框写法），否则 `[data-test] textarea` 选择器为空。② el-dropdown 测试用 `findComponent(ElDropdown).vm.$emit('command', ...)` 驱动，绕开 teleport 弹层的不稳定；`@command` 内联箭头参数要显式标类型（`(cmd: string|number|object)`）否则 vue-tsc 报隐式 any。
- 怎么自证：`pnpm test` 全量 `25 files / 168 passed`；`pnpm typecheck` 无错；`pnpm build` 成功；`pnpm lint` 通过。
- 反向验证：① 编辑「底部新增、原消息不变」用例断言 messages[0] 仍为原文且存在新内容——若误改成原地修改历史，messages[0] 变化而红。② AI 复制「流式中不显示」仍由 canCopy 守。

## conversation ⑦ 会话管理 UI 调整#2（图标全移气泡外 + hover，2026-07-01）
- 按反馈再调：用户消息的复制/编辑图标从气泡内移到**气泡外右下角**、hover 才显现；AI 复制图标也从常显改为 **hover 才显现**。两侧对称——图标统一进气泡外的 `.chat__ops`（默认 opacity:0，`.chat__row:hover` 显现），左右对齐沿用 row 的 align-items（用户 flex-end 右、AI flex-start 左）。删除旧的 `.chat__bubble-ops`/`.chat__copy-external`。
- TDD：新增结构测试「用户操作图标在气泡外」——断言 `.chat__bubble` 内找不到 copy/edit（`bubble.find(...).exists()===false`）、但行内（气泡外）能找到（`wrapper.find(...).exists()===true`）。先红（原在气泡内）→ 移出后绿。
- 怎么自证：`pnpm test` 全量 `25 files / 169 passed`；`pnpm typecheck`、`pnpm build`、`pnpm lint` 均通过。
- 反向验证：若把 ops 放回气泡内，「用户操作图标在气泡外」用例因 bubble 内 exists()===true 而红。

## conversation ⑦ 会话管理 UI 调整#3（间距/尺寸 + 发送按钮内嵌，2026-07-01）
- 纯样式/布局微调（ChatView.vue）：`.chat__ops` 图标间距 8→16px、`.chat__op` font-size 18px（图标放大）；`.chat__row` gap 4→8px（气泡与图标距离，用户与 AI 同）；`.chat__list` 加 `padding-right:12px`（消息离右侧滚动条）；发送按钮移入输入框内——`.chat__input-box` 相对定位、`.chat__send` 绝对定位右下、`:deep(.el-textarea__inner)` padding-bottom:44px 让文字不被按钮遮；输入框 rows 2→4 加高。
- 无新测试（纯 CSS/布局，行为不变）：`data-test="chat-input"`（容器）/`chat-send`（按钮）锚点保留，`[data-test="chat-input"] textarea` 与 chat-send 选择器仍命中。
- 怎么自证：`pnpm vitest run ChatView.spec` → 16 passed；`pnpm typecheck`、`pnpm lint` 通过。数值系视觉初值，待登录实测微调。

## 2026-07-02 knowledge K1 知识库管理（dataset CRUD）

- [x] V13 迁移照 V7 约定（identity 主键 / text+check / 软删 / timestamptz / 部分唯一索引）
- [x] 路由 /api/v1/knowledge/datasets 逐条核对 api-standards（成员族带模块段、复数资源、PUT 全量、无 PATCH、软删幂等）
- [x] 错误码零新增，全部复用 CommonError；DTO 不 import entity（ArchUnit 绿）
- [x] 后端 mvn test 全绿（无 -q）；前端 pnpm test + typecheck 全绿
- [x] 手动验收：建库 → 重名 409 → 改名 → 他人账号禁用态 → 删除 → 同名重建

## 2026-07-02 knowledge K2 文档上传与分段

- [x] V14 只新增两表；kb_chunk 无 embedding 列（K3 迁移补 vector(1024)）；FK 无 cascade（软删体系）
- [x] 路由核对 api-standards：嵌套一级 /datasets/{id}/documents；documents 升顶级 /documents/{id}/chunks
- [x] 错误码仅新增 15001/15004（15004 为 api-standards 预定义号）；multipart 超限/缺文件转 10001
- [x] 分段参数走 yml（500/50）并记录在 kb_document 行；Db.saveBatch ≤1000 + reWriteBatchedInserts
- [x] 文档列表排除 content 大列；删除级联软删（库→文档→分段）
- [x] 后端 mvn test 全绿（无 -q）；前端 pnpm test + typecheck 全绿
- [x] 手动验收：传 txt/md → 看分段数与状态 → 传 pdf 报 15004 → 空文件报 15001 → 分段预览翻页 → member 门控 → 删文档/删库级联

## 2026-07-08 conversation 聊天引用来源展示

- [x] 持久化：新增 V20 `message.sources jsonb not null default '[]'`，`MessageSource` DTO 放 `conversation.dto`，`MessageSourcesTypeHandler` 复用 AppConfig jsonb 手法，`appendAssistant` 末尾新增 sources 参数并随 assistant 消息落库。
- [x] 编排：`augmentWithKnowledge` 返回 `Augmented(prompt,sources)`；未绑库、检索失败降级、命中空均 sources=`[]`；命中时仍用全文拼 prompt，只把截断 preview 存来源快照，长度走 `hify.conversation.source-preview-length` 配置。
- [x] 协议：`MessageView.sources` 暴露历史/同步返回；SSE 只新增 `sources` 事件，位于 `meta` 后、首个 `message` 前，空来源不发；`meta/message/done/error` 结构未改。
- [x] 前端：`useChatStream` 解析 `sources`，Pinia store 把来源挂到助手消息；ChatView 在助手气泡下用 Element Plus `el-collapse`/`el-tag` 展示折叠来源卡片（文档名、分数、预览），纯展示不可点。
- [x] 验证：后端 `mvn -q test` 全绿（含 V20 连库往返、Modulith/ArchUnit）；前端 `pnpm vitest run && pnpm build` 全绿。
- [x] 留账：无新留账；卡片点击/全文展开/计量/Rerank/来源反查按 spec 明确不做，后续需求再开轮。

## 2026-07-08 E2E 地基 + KB 黄金旅程（Playwright）

对应设计：`docs/superpowers/specs/2026-07-08-e2e-golden-journey-design.md`；对应架构文档：`docs/architecture/testing-standards.md` §5。

**做了什么**：新建 `web/e2e/` 前端测试基建——`playwright.config.ts`（`webServer` 数组拉起 后端(e2e profile)+假LLM桩+前端，逐个等健康 URL）、`stub/llm-stub.mjs`（Node 原生 http，OpenAI 兼容协议，`/v1/embeddings` 固定 1024 维向量 + `/v1/chat/completions` SSE 固定答案 + `/health`）、`support/reset-db.mjs`（`pnpm e2e` 脚本里跑在 Playwright 之外，先于后端启动重置 `hify_e2e` 库）、`fixtures/kb-doc.txt`（测试文档）、`golden-journey.spec.ts`（穿 identity→provider→knowledge→app→conversation 五模块的黄金旅程，8 步：登录→建供应商→建对话/embedding 两模型→建知识库→传文档等 ready→建应用绑库→聊天看引用→刷新页面引用仍在）、`smoke.spec.ts`（种子 admin 能登录的最小冒烟）。后端新增 `server/src/main/resources/application-e2e.yml`（`e2e` profile，数据源指 `hify_e2e`，bootstrap-admin 账密 `admin`/`e2e-admin-123`）。旅程触及的 provider/knowledge/app 视图按需补了 `data-test` 钩子（无关重构未做）。

**DoD 清单逐项勾选**（对齐 spec §5）：

- [x] 尺子 1·故意搞错必须变红——三处均真改坏→跑→在预期断言处确认变红→改回，`git status --short` 确认工作区干净（详见下方三次变异记录，完整过程见 `.superpowers/sdd/task-5-report.md`）。
- [x] 尺子 2·断言钉后端/DB 产出——引用断言用上传的真实文件名 + `[0,100]%` 数字分数，不断言桩那句固定答案的字面；桩全程保持「傻」，无任何针对断言的特判分支。
- [x] 尺子 3·覆盖面声明清楚——旅程真穿 identity/provider/knowledge/app/conversation 五模块，除 admin 登录用种子账号外无一步抄近路；推迟项已写明（负样本「问无关→无引用/降级」、CI、其它旅程、跨浏览器矩阵、视觉回归）。
- [x] `pnpm e2e` 本地一条命令跑绿：`golden-journey.spec.ts` + `smoke.spec.ts` 2 passed，dev 库 `hify` 全程未被触碰（重置只作用于 `hify_e2e`）。
- [x] 不新增运行时依赖：`@playwright/test` 为 devDependency，桩用 Node 原生 `http`。
- [x] `web/e2e/**` 不进 vitest 扫描范围（`vite.config.ts` 的 `test.include` 为 `src/**/__tests__/**/*.{test,spec}.ts`），vitest 单测不受影响。

**三次故意搞错的实测结论**（完整改动/输出见 `.superpowers/sdd/task-5-report.md`，均已还原、`git status --short` 为空）：

1. **检索真在跑（正交向量）**：把桩 `/v1/embeddings` 改成按调用计数返回正交向量（首次仍返回全 0.1 保系统设置探测通过，此后热点维度轮转），使文档分段向量与提问向量余弦相似度趋近 0、跌破检索阈值。`pnpm e2e -- golden-journey.spec.ts` → **FAIL，断在预期处**：live 阶段「msg-sources visible」（`golden-journey.spec.ts:96`）——证明检索 SQL + 阈值判断真在跑，不是摆设。
2. **来源真落库（不写 sources）**：把 `ConversationStore.appendAssistant` 里的 `m.setSources(sources);` 注释掉。`pnpm e2e -- golden-journey.spec.ts` → **FAIL，断在预期处**：第 9 步「刷新后 msg-sources 可见」（`golden-journey.spec.ts:105`），且 live 阶段第 96 行断言**照常通过**（证明 SSE 实时展示的引用不依赖持久化）——精确证明 `message.sources` 落库这条链路被旅程覆盖，断点与变异①不同、二者互相区分。
3. **绑定+注入真串起（解绑库）**：把旅程第 7 步创建 app 时绑定知识库的两行操作注释掉。`pnpm e2e -- golden-journey.spec.ts` → **FAIL，断在预期处**：live 阶段「msg-sources visible」（因注释行号后移落在 `golden-journey.spec.ts:97`）——证明 app↔dataset 绑定关系与 conversation 检索注入真的串联，不是前端凭空渲染。

三处变异全部断在预期的具体断言行、互相区分，无「断在无关错误」或「意外仍然绿」的情况；旅程真实覆盖了检索阈值判断、sources 持久化、app↔dataset 绑定注入三条链路。复跑 `pnpm e2e -- golden-journey.spec.ts` 全部复绿（2 passed）。

**验证命令**：
- `cd web && pnpm e2e` → 全绿（旅程 8 步 + 冒烟，dev 库未被触碰）。
- `cd web && pnpm vitest run` → 全绿，`web/e2e/**` 未被 vitest 扫到（include 只匹配 `src/**/__tests__/**`）。

**留账（本轮不做，已在 testing-standards.md §5.6 声明）**：CI/GitHub Actions 集成；负样本路径（问无关问题→无引用/降级）；workflow/agent 等其它旅程；跨浏览器矩阵；视觉回归/截图比对。

## 2026-07-10 Workflow W1 引擎闭环

- DoD 验证：`mvn -f server/pom.xml test -Dtest=WorkflowRunFlowTest` → `Tests run: 3, Failures: 0, Errors: 0`，`BUILD SUCCESS`；覆盖建 workflow app、保存草稿、同步触发、run/node_run 三节点日志、历史与详情查询。
- 全量回归：`mvn -f server/pom.xml verify` → `Tests run: 514, Failures: 0, Errors: 0`，`BUILD SUCCESS`；已包含 `ModularityTests` 与 `LayerRulesTest`。
- Schema 验证：`WorkflowSchemaTest` 真库确认 `workflow_def`、`workflow_run`、`workflow_node_run` 三表，`workflow_node_run` 月分区、必需索引、jsonb/check/外键与 autovacuum 参数。
- 事务与 IO：`WorkflowEngine` 在事务外顺序驱动节点；`WorkflowRunStore` 只做短事务落库；LLM 调用集中在 `LlmNodeExecutor`，不进入 `@Transactional` 方法。
- 收尾留账：Task 11 Step 5 的真实环境 curl 手动验收未由 Codex 执行，按计划留给人工；全量回归曾暴露既有 `DocumentProcessStore` 使用 MyBatis-Plus 静态 `Db.saveBatch` 在多 Spring 测试上下文下可能拿错 SqlSessionFactory，已改为注入的 `KbChunkMapper` 在当前事务内写入。

## 2026-07-10 Workflow W1 终审修缮（Claude 复审 Codex 交付）

- 复审结论：W1 主体合格（迁移/事务红线/API/测试逐条对过规范）；两处修缮 + 一处规范修订，均经用户拍板。
- 修缮 1（拍板：换批量写法）：Codex 为修多上下文测试把 chunk 批量写从 `Db.saveBatch` 降级为逐条 insert，与 database-standards §2.1 冲突且生产性能回退。改为 `KbChunkMapper.insertBatch` **多值 insert**（foreach 拼 values，每批 ≤ 1000），留在调用方 Spring 事务内；不能用 BATCH 执行器会话——同事务内与 SIMPLE 执行器互斥（mybatis-spring 抛 Cannot change the ExecutorType）。规范 §2.1 已同步改为多值 insert 并注明禁用静态 `Db.saveBatch` 的原因；`DocumentProcessJobTest` 清掉失效的 `mockStatic(Db)` 脚手架。新增 1001 段跨批测试。
- 修缮 2（拍板：现在修）：`WorkflowRunService.run` 给 `engine.execute` 加兜底 try-catch——落库等非预期异常时先 `markRunFailed("系统异常，执行中断")` 再上抛，否则 run 永久卡 running（僵尸自愈只在重启跑）。顺带补可观测性：LLM 调用失败经 `NodeExecutionException` 携带渲染后 inputs 落 `node_run.inputs`（排障能看到实际发出的提示词），失败文案取真实 cause。
- 复审全量回归：`mvn -f server/pom.xml verify` → `Tests run: 517, Failures: 0, Errors: 0`，`BUILD SUCCESS`（514 + 新增 3）。
- 留给画布轮的备忘：`GraphNode`/`GraphEdge` record 无 `position`/`sourceHandle` 字段，graph 经 Java 往返会丢未知字段——**前端接画布前必须先加这些字段**，否则画布坐标被静默丢弃。
- 手动验收（DoD Step 5）：2026-07-10 用户用 `docs/postman/workflow-w1.postman_collection.json` 真实环境实测通过——黄金链路（建应用→存草稿→触发 succeeded→详情/游标历史）+ 三条失败路径（图非法 18001、缺必填输入 10001、模型不可用 HTTP 200 但 run=failed）。**W1 全部 DoD 闭环。**

## 2026-07-10 Workflow W2 知识检索节点

- 本轮范围：新增 `knowledge-retrieval` 节点类型；`GraphValidator` 校验 `datasetIds/query` 格式；`KnowledgeRetrievalNodeExecutor` 调 `KnowledgeFacade.validateDatasetIds/retrieve` 并输出 `text/count`；`WorkflowRunFlowTest` 覆盖 start → kb → llm → end RAG 黄金链路与已删库失败路径；新增 `docs/postman/workflow-w2.postman_collection.json`。
- 拍板决策：检索失败不降级而是节点 failed；输出固定为 `text + count`；保存草稿只做格式校验、知识库存在性运行时校验；检索结果拼接留在 executor 内，不抽公共抽象。
- 测试结果：`mvn -Dtest=GraphValidatorTest test` → 17 passed；`mvn -Dtest=KnowledgeRetrievalNodeExecutorTest test` → 3 passed；`mvn clean test -Dtest=WorkflowRunFlowTest` → 5 passed（清理 target 是为排除 Maven 增量编译残留）；`mvn verify` → `Tests run: 527, Failures: 0, Errors: 0`，退出码 0，含 `ModularityTests` 与 `LayerRulesTest`。
- DoD 待办：用户用 W2 Postman 集合在真实服务/真实模型/真实已向量化知识库上手动跑两条路径：黄金链路 succeeded 且回答引用知识库内容；不存在库 id 保存成功但触发后 HTTP 200 + run failed + kb node_run 可排障。

## 2026-07-11 Workflow W3a 条件分支节点

- 本轮范围：新增 V22 放宽 `workflow_node_run.status` check 为 `running/succeeded/failed/skipped`；`GraphEdge.sourceHandle` 与 `GraphNode.position` jsonb 往返保真；新增 `condition` 节点类型、`ConditionEvaluator`、`ConditionNodeExecutor`；`GraphValidator` 增加 condition 字段/operator/出边 handle 规则；`WorkflowEngine` 增加拓扑序 + 活边判定，未选中路径落 `skipped` node_run，跳过节点引用渲染为空串；`WorkflowRunFlowTest` 增加命中/未命中两方向真库集成测试；新增 `docs/postman/workflow-w3a.postman_collection.json`。
- 五个拍板决策：condition 只做单条比较；只做二路 `true/false` 分支；未选中路径节点必须落 `skipped` 记录；引用被跳过节点字段时渲染为空串；执行模型保留拓扑序遍历，在每个节点执行前做活边判定。
- 测试结果：`mvn -Dtest=ConditionEvaluatorTest test` → 7 passed；`mvn -Dtest=GraphValidatorTest test` → 23 passed；`mvn -Dtest=ConditionNodeExecutorTest test` → 3 passed；`mvn -Dtest='WorkflowEngineBranchTest,WorkflowRunServiceTest,GraphValidatorTest' test`（需提升权限允许 Mockito attach）→ 36 passed；`mvn -Dtest=WorkflowRunFlowTest test` 普通沙箱因无 Docker 失败，提升权限后在全量回归中覆盖；`mvn verify` 普通沙箱因 Mockito attach 失败，提升权限后 → `Tests run: 551, Failures: 0, Errors: 0`，退出码 0，含 `ModularityTests` 与 `LayerRulesTest`。
- DoD 待办：用户用 W3a Postman 集合在真实服务/真实模型/真实已向量化知识库上手动跑三条路径，验收前必须重启服务：命中方向 succeeded 且 `llm_miss=skipped`；未命中方向 succeeded 且 `llm_hit=skipped`、`if_1.inputs.left="0"`；失败路径文本进数字比较 → HTTP 200 + run failed + `if_1` node_run 可见实际比较值。
- 终审修缮（用户拍板）：删除执行期合成直线假边的 4 参 `WorkflowEngine.execute` 兼容重载（生产零调用、对非直线图语义错误），`WorkflowEngineTest` 5 处调用改为显式传边——该重载源于计划漏列 `WorkflowEngineTest`（列文件命令 `| head` 截断），Codex 为守「不动计划外文件」红线所做的合理妥协。修缮后复跑 `mvn verify` → 551/0/0，退出码 0。

## 2026-07-11 Workflow W3b HTTP 请求节点

- 本轮范围：新增 `infra/outbound` 出站 HTTP 地基（`OutboundProperties`、`SsrfValidator`、`OutboundHttpClient`、`OutboundResponse`）；新增 `http` 节点类型与 `GraphValidator` 校验；新增 `HttpNodeExecutor`，输出 `status/body/headers`，请求输入落库前对敏感头脱敏；`WorkflowRunFlowTest` 增加 http → condition → llm_ok/llm_fb 两方向真库集成测试；新增 `docs/postman/workflow-w3b.postman_collection.json`。
- 四个拍板决策：非 2xx 不算节点失败，交给 condition 按 `status` 分流；3xx 一律不跟随，`status+Location` 原样输出；内网白名单暂缓，一期只调公网，未来预留由 `SsrfValidator` 查 `system_setting` 放行；实现使用 JDK 21 `HttpClient`，不新增依赖。
- SSRF 与 DNS pinning：发请求前对 DNS 解析后的全部 IP 校验，拦截 loopback/RFC1918/link-local/any/组播/IPv6 ULA，容器服务名由私网解析结果覆盖；一期不做 DNS pinning，校验与连接之间的 rebinding 窗口按内网部署+受信团队威胁模型接受，二期对外开放时收紧。
- 测试结果：`mvn -Dtest=SsrfValidatorTest test` → 8 passed；`mvn -Dtest=OutboundHttpClientTest test` 普通沙箱因本地 socket 监听失败，提升权限后 → 8 passed；`mvn -Dtest=GraphValidatorTest test` → 28 passed；`mvn -Dtest=HttpNodeExecutorTest test` 普通沙箱因 Mockito agent attach 失败，提升权限后 → 5 passed；`mvn -Dtest=WorkflowRunFlowTest test` 普通沙箱因 Docker socket 失败，提升权限后 → 9 passed；`mvn verify` 提升权限后 → `Tests run: 579, Failures: 0, Errors: 0`，退出码 0，含 `ModularityTests` 与 `LayerRulesTest`。
- DoD 待办：用户用 W3b Postman 集合在真实服务/真实模型上手动跑四条路径，验收前必须重启服务：公网成功 `httpbin.org/json` → succeeded 且 `llm_fb=skipped`；404 分流 → succeeded、`http_1.outputs.status=404` 且 `llm_ok=skipped`；SSRF 拦截 `127.0.0.1` → HTTP 200 + run failed + errorMessage 含“禁止访问”；详情中 `http_1.inputs.headers.Authorization=***`。

## 2026-07-11 检索阈值调优（评估集 + score-threshold 0.3→0.4）

- 本轮范围：新顶层 `scripts/retrieval-eval/`（4 篇合成语料 1508~1530 字符 + 16 应命中/12 不应命中问题集 + `report.mjs` 指标纯函数含 9 个 node:test 单测 + `eval.mjs` 编排 CLI + README）；CLAUDE.md 仓库布局补 `scripts/` 一行；评估拍板后 `score-threshold` 默认 0.3→0.4。零 npm 依赖、后端仅改 yml 默认值与一处测试注释、零迁移。
- 四个拍板决策（spec 入档）：可复跑评估集（非只手工调数字）；语料随 repo 走；手跑工具不进 mvn test/CI；一次检索（topK=10, threshold=0）离线扫 11 档阈值，embedding 调用数=题数 28。
- Codex 执行：两次按红线停报，均为**计划缺口**而非执行偏差——① 语料长度未达自校验下限（计划写 1500 字实落 663~848，扩写修复）；② `node --test <目录>` 形态在 Node 24 失效（本机复现后改显式指定测试文件）。终审逐字节比对 9 个落盘文件与计划「原样写入」块全部一致，提交/文件构成与计划一一对应，零删弱零夹带。
- 评估结果（模型「向量解析」，2026-07-11）：现值 0.30 有 8.3% 误命中（0.25/0.20 达 25%/75%，W3a 症状坐实）；0.35~0.50 四档误命中 0、召回 93.8%（15/16）；分隔带宽（不应命中最高 0.33 vs 应命中期望文档中位 0.62）。拍板 **0.40**：距误命中天花板 +0.07、距召回掉档的 0.55 有 0.15，双向留余量。
- 怎么自证：`node --test scripts/retrieval-eval/report.test.mjs` → 9/9 pass 退出码 0；真实环境 `eval.mjs` 一条命令出报告且评估库自动清理；改 yml 后 `mvn verify` → 579/0/0 退出码 0（surefire 报告聚合，非 grep BUILD SUCCESS）。
- 待人工手验（改 yml 后需**重启服务**）：① 真实知识库命中测试问 2~3 个无关问题 → 0 命中，相关问题仍命中（防矫枉过正）；② conversation 绑库应用问无关问题 → 无引用卡片、正常降级回答；③ 可选：W3a 工作流未命中方向用真实库重验 count=0 分流；④ DoD 剩余：评估脚本重跑一次（防重名）+ `--keep` 跑一次到前端复查后手动删库。
- 留账：评估语料为合成，代表性有限（已由真实库抽查兜底）；per-dataset 阈值、topK 调优、Rerank/混合检索仍不做（spec 边界）；报告头模型名取自 admin 设置的显示名。

## 2026-07-12 Workflow 画布 C1（画布地基+保存）

- 本轮范围：后端 `GraphValidator` 拆 `validateBasics`（保存草稿底线校验）/`validateAndOrder`（运行全量校验，行为不变）；前端装 @vue-flow/core+background+controls，新增 `/apps/:appId/workflow` 画布页（受控 Vue Flow、左栏拖拽、连线、保存/离开守卫、非 owner 只读）、GraphDef↔VueFlow 纯转换层（边 id 确定性生成/网格兜底/往返保真/节点 id 自增）、AppList 支持创建 workflow 应用与编排入口。零迁移、零新错误码。
- 拍板决策（spec 入档）：三子轮 C1→C2→C3；保存校验放宽；左栏拖拽+右侧抽屉（抽屉归 C2）；手动保存不自动保存；预置 start/end 不预连线。
- 测试结果：`mvn -f server/pom.xml verify` → `Tests run: 587, Failures: 0, Errors: 0`，退出码 0，含 `ModularityTests` 与 `LayerRulesTest`；`cd web && pnpm test && pnpm typecheck && pnpm build && pnpm lint` → Vitest 38 files / 276 tests passed，typecheck/build/lint 退出码 0；`cd web && pnpm e2e` → 2 passed，退出码 0。
- DoD 待人工验收（重启服务后）：spec §8 七条。
- 留账：C2 配置抽屉/未配齐标红；C3 运行调试；运行历史页推迟发布轮；E2E workflow 旅程推迟。

## 2026-07-12 Workflow 画布 C2（六类节点配置抽屉）

- 本轮范围（纯前端，server/ 零改动）：`GraphNode.data` 收窄为按类型联合；右侧 el-drawer 配置抽屉 ×6 类表单（start 输入声明 / llm 模型+提示词含失效兜底 / kb 数据集多选含已删兜底+query / condition 三元组 / http method+url+headers 行编辑+body / end 输出声明行编辑）；未配齐橙色徽章+tooltip（`useNodeIssues` 镜像后端 require* 规则，字段级、提示不阻断）；可引用变量面板（`useUpstreamVars` 沿入边反向 BFS 祖先、防环）点击插入光标处（`useVarInsert` 最后聚焦字段+默认字段回落）；表单即时写回 `updateNodeData`，dirty/保存/离开守卫复用 C1 机制；非 owner 抽屉只读。
- 拍板决策（spec 入档）：变量面板点击插入（不做输入框内 `{{` 自动补全）；徽章+tooltip 不动边框；即时写回无确定按钮；组件架构=抽屉壳+六个子表单（否决大组件 v-if 与 schema 渲染器）。
- Codex 执行：15 个 Task 15 个 commit 与计划一一对应，账本纪律良好（只勾选不改内容）；但 **Task 16 全量回归三步未执行未勾选**，由终审代跑补勾。
- 终审修缮（1 Critical + 1 Important + 2 顺手）：① VariablePanel 模板残留孤立 `>` 文本节点（UI 渲染多余字符）——根因是 Codex 遇到 el-tag 点击测不通，把 @click 挪进内层 span 重排模板时留下孤儿字符；真因是 **el-tag 根是 `<transition>`，VTU 默认 stub 截走 attrs/监听**，正解是组件保持 @click 在 el-tag、测试 `stubs: { transition: false }`。② EndForm/HttpForm 动态行注册只增不删，聚焦过的行被删后点变量标签命中陈旧闭包**静默失效**——`useVarInsert` 加 `unregister`，两表单注册随行数 prune。③ 连带发现 **el-input 的 focus 组件 emit 在 jsdom 触发不了**（capture 监听+组件 emit 链路），原「最后聚焦」特性从未被真实测过——全部表单 `@focus`→`@focusin`（原生冒泡，浏览器/jsdom 两端一致），并补 ConditionForm focusin 正向用例钉死机制。④ useNodeIssues 注释补口径（API 手拼草稿的元素级偏差留给运行时校验）。共补 5 条回归测试。
- 测试结果：`pnpm test` → 49 files / 340 tests passed；`pnpm typecheck`/`pnpm lint`/`pnpm build` 退出码 0；`git status server/` 无输出（防呆确认零后端改动）+ `mvn -q verify` 退出码 0（surefire 88 份报告，非 grep BUILD SUCCESS）。
- DoD 待人工验收（纯前端，`pnpm dev` 即可，后端无需重启）：spec §7 五条——纯画布配出 W3a 等价图保存后 Postman 运行 succeeded；徽章列缺失项且配齐消失、半成品可存可恢复；变量面板=祖先输出、点击插入；非 owner 全只读；失效模型/已删知识库禁用兜底。
- 留账：输入框内 `{{` 自动补全二期候选；图级校验提示不做（18001 兜底）；headers 空行编辑的边缘 dirty（极边缘）；LlmForm/KnowledgeForm 为测试 expose selectOptions（测实现不测行为，二期改断言渲染）；el-input 包 div[data-test] 的写法与计划不一致（可用，风格账）。

## 2026-07-12 Workflow 画布 C3（运行调试）

- 本轮范围（纯前端，server/ 零改动，run API W1 已备齐）：工具栏「运行」按钮（dirty 先自动保存，「所见即所跑」）→ start 有声明输入弹 `RunInputsDialog`（必填红星+非空校验+预填上次输入）→ `runWorkflow` 同步触发（专用超时 `workflowRunTimeoutMs=300s`）；节点状态徽章（succeeded 绿✓/failed 红✗/skipped 灰，经 `NODE_RUNS_KEY` provide/inject 进 CanvasNode）；抽屉「配置/运行」双 tab（有结果默认落运行 tab，`NodeRunPanel` 展示输入/输出 JSON/错误/耗时/skipped 空态）；工具栏 `RunStatusChip` 状态条（成功+耗时/失败，popover 看最终输出/整体错误）；StartForm 补「必填」勾选（顺手修 updateRow 改名丢字段隐患）。核心 `useWorkflowRun` 状态机：HTTP 错误（运行未发生）保留旧结果、图一改（dirty）即清空、世代号丢弃运行中改图的过期响应、canSave=false（非 owner）跳过自动保存。
- 拍板决策（spec 入档）：dirty 自动保存再运行；结果载体=复用配置抽屉加 tab；总结果入口=工具栏状态条（不自动弹抽屉不只靠 toast）；补 required 勾选；无声明输入直接跑；结果只存内存刷新即无。
- Codex 执行：Task 1-9 九个 commit 与计划一一对应、逐文件对账基本零偏差；但 **Task 10 全量回归再次未执行未勾选（C2 后第二次，提示词已写明仍被跳过）**，终审代跑补勾。
- 终审修缮（1 处）：RunInputsDialog 同时用 `el-form-item :error` 和手写错误 div → 真实浏览器「必填项不能为空」显示两遍。根因：**Element Plus form-item 的错误文案经 `refDebounced(validateState, 100)` 100ms 防抖才上 DOM**，jsdom 测试等 nextTick 看不到 → Codex 被失败测试逼着加手写 div 却没删 `:error`。正解：保留原生 `:error`（自带输入框红边框），测试用 `vi.waitFor` 轮询等防抖。此为第三个 jsdom×Element Plus 坑（前两个：el-tag transition stub、el-input focus 链路）。
- 测试结果：`pnpm test` → 53 files / 372 tests passed；`pnpm typecheck`/`pnpm lint`/`pnpm build` 退出码 0；`mvn verify` → 退出码 0 且 surefire 报告聚合无失败（非 grep BUILD SUCCESS）；`git status --porcelain server/` 无输出。
- DoD 人工验收（用户已确认通过）：spec §6 五条——分支两方向徽章正确（命中绿/未走灰 skipped）；配错模型 → 200+failed、失败节点红✗、抽屉运行 tab 见错误；必填输入弹窗拦空、最终输出可见、重跑预填；改图即清徽章、再运行自动保存跑新图；未配完草稿 → 18001 toast 不产生新徽章。
- 留账：运行历史列表页推迟发布/对外 API 轮；运行取消/SSE 节点推进动画不做；运行中改图响应丢弃为接受的边缘情况；超长链路（>300s）超时时调 `workflowRunTimeoutMs`；E2E workflow 旅程继续推迟。

## 2026-07-13 Workflow 代码执行节点 + Python 沙箱容器

- 本轮范围：新增 `sandbox/` Python 标准库单文件 HTTP 服务（`/health`、`/run`，子进程隔离、CPU/内存 rlimit、执行超时、输出上限）与 Dockerfile；docker-compose 新增 sandbox 服务（read_only/cap_drop/no-new-privileges/tmpfs/资源限制）和 `sandbox-net`；infra 新增 `SandboxClient/SandboxProperties/SandboxResult`（JDK HttpClient、双超时、响应超限、并发信号量）；workflow 新增 `NodeType.CODE`、`WorkflowError.CODE_EXECUTION_FAILED=18002`、`CodeNodeExecutor`、`GraphValidator` code 字段校验；前端新增 code 类型、`CodeForm`、调色板/画布节点/抽屉接线。
- 测试结果：`cd sandbox && python3 -m unittest test_sandbox_server -v` → 8 passed；后端全量 `cd server && mvn -q test`（本仓库无 `server/mvnw`，需提升权限支持 Mockito attach/Testcontainers Docker）→ exit code 0；前端 `cd web && pnpm vitest run && pnpm vue-tsc --noEmit && pnpm lint && pnpm build` → 54 files / 379 tests passed，typecheck/lint/build exit code 0。
- 容器验收输出（`sandbox-net internal:true` 后宿主机端口在当前 Docker Desktop 环境未实际发布，改用容器内 HTTP 调 localhost 验证协议与隔离）：`/health` → `{"status": "ok"}`；正常执行 → `{"ok": true, "outputs": {"y": 42}}`；死循环超时 → `{"ok": false, "error": "执行超时（500ms）"}`；外网连接 → `{"ok": false, "error": "执行出错：OSError: [Errno 101] Network is unreachable"}`。
- 偏差记录：① 计划写 `cd server && ./mvnw`，实际仓库只有 `server/pom.xml`、无 Maven wrapper，使用系统 `mvn` 等价执行；② 普通工具沙箱下本地 socket/Mockito attach/Docker/Testcontainers/host curl 受限，相关命令按权限规则提升后通过；③ Task 2 初始冒烟因普通工具沙箱访问宿主机端口返回 curl exit 7，提升权限后正常；④ 计划中 `sandbox-net` 只写 `driver: bridge`，实际无法阻断出网，按 architecture/deployment 安全边界补 `internal: true` 并单独提交；⑤ `internal:true` 在当前 Docker Desktop 下导致宿主机端口未实际发布，生产安全形态满足，宿主机本地联调需后续用 server 容器同网或额外本地 profile 解决；⑥ Task 6 `vue-tsc` 在 Task 8 接线前因 `Record<WorkflowNodeType,...>` 缺 code 暂红，Task 8 补齐后全量类型检查通过；⑦ `CodeForm.vue` 中计划给的 `{{ '{{code_1.key}}' }}` 在当前 Vue 编译器下解析失败，改为 `v-text` 输出同一字面量。

## 2026-07-13 Agent Tool T1 Foundation（内置工具 + 手动 tool-calling）

- 本轮范围：新增 `tool` 统一注册表（V23，播种 `http_request` / `code_executor` 两个内置工具）；内置工具执行器复用 `OutboundHttpClient` 与 `SandboxClient`；`ToolFacade` 暴露 enabled 内置工具的 Spring AI `ToolCallback`；`app.config.agentEnabled` 增加 Agent 开关；`message.tool_calls` jsonb 映射工具调用轨迹；`AgentChatService` 手动 tool-calling 循环（禁内部自动执行、累计 token、步数上限）；`ConversationService.send` 按 app 开关分流，SSE 对 Agent 应用以 17002 拒绝。
- 验证：Task 1-7 均先红后绿并单独提交；Task 8 执行 `mvn -q clean test` 全量回归，退出码 0，surefire 聚合 `tests=623 failures=0 errors=0 skipped=0`（含 `ModularityTests`/`LayerRulesTest`）。
- 手动 self-check：当前 compose 只有 `postgres`/`sandbox`，本机 `localhost:8080/actuator/health` curl exit 7，缺少运行中的 server、可登录 token、可用 chat 模型与 chat app，故 spec §1 的真实 Agent curl 黄金路径未执行；需启动 server 并准备可用应用后补跑。
- 留账：T1 只支持全量启用内置工具，per-app 工具选择 `app_tool_rel` 留 T2；OpenAPI 工具留 T3；MCP 服务与 `mcp_server` 留 T4；Agent 流式/SSE 留 T2。
- 终审补验（2026-07-14，起默认 profile server 对真库 hify 实跑，验毕已清现场）：
  - 独立复跑 `mvn clean test`：Tests run **623, Failures 0, Errors 0, Skipped 0**（含 ModularityTests/LayerRulesTest）。
  - 启动：Flyway `Successfully applied ... now at version v23`；`Started HifyApplication`（全新 bean ToolFacade/AgentChatService/AgentProperties 装配无误）；`tool` 表种子 2 行（`http_request`/`code_executor`，enabled、owner_id/spec 空）。
  - **17002 守卫**（app 14 翻 agentEnabled 后打 SSE 端点）：`{"code":17002,"message":"Agent 应用暂不支持流式对话"}` HTTP 400 ✓。
  - **真实 LLM 黄金路径**（app 14=deepseek，同步端点）：
    - http_request：模型调 `GET https://httpbin.org/json`，工具真执行取回 HTTP 200 全 JSON，`message.tool_calls` 落一条轨迹(name/args/result)，模型据结果答出 `Sample Slide Show` ✓（promptTokens=1387，证明工具 schema+结果注入）。
    - code_executor：模型生成正确 Python(`sum(range(1,101))`) 发起调用；因 host 直跑 server 连不到 `internal` 沙箱网络（[[workflow-code-node-merged]] 已记坑，非代码缺陷），工具返回错误文本→模型据错恢复直接答出 5050；`tool_calls` 落 2 条轨迹，证明手动循环把工具错误喂回、有界重试后退出 ✓。
  - 结论：Spring AI `internalToolExecutionEnabled(false)` 下 tool call 确被交回手动循环、工具真执行/结果回填/轨迹落库/错误恢复全链路成立。code_executor 端到端「绿」需 server 与 sandbox 同网（进 compose）后复验，与 T1 代码无关。

## 2026-07-14 Agent Tool T2（per-app 工具配置 + Agent 流式轨迹）

- 本轮范围：新增 `app_tool_rel` V24 与实体/Mapper；`ToolFacade` 增加 per-app `getToolCallbacks(ids)` 与 `validateToolIds(ids)`；成员侧 `GET /api/v1/tool/tools`；`AppResponse/CreateAppRequest/UpdateAppRequest/AppRuntimeView` 打通 `toolIds`；`MessageView` 回显 `toolCalls`；SSE 增加 `tool_call` 事件；`AgentChatService` 增加工具调用完成回调；`ConversationService.sendStream` 对 Agent 应用去 17002，改为 boundedElastic 上复用同步 Agent 循环并实时推工具轨迹；前端应用配置页增加 Agent 开关与工具多选；前端流式接线把 `tool_call` append 到助手消息；ChatView 复用参考来源折叠样式渲染工具调用轨迹。
- 逐 Task 自证（均按计划先红后绿、逐 Task commit）：
  - Task 1：`mvn test -Dtest=AppToolRelMapperIT` → 1 passed；Flyway 真库应用 V24，插入并按 appId/id 读回工具绑定。
  - Task 2：`mvn test -Dtest=ToolRegistryTest,ToolFacadeImplTest` → 8 passed；覆盖按 id 取 callback、空集合不查库、enabled id 过滤、不可用 id 抛 `CommonError.PARAM_INVALID`。
  - Task 3：`mvn test -Dtest=ToolServiceTest,ToolControllerTest` → 2 passed；成员族路由 `/api/v1/tool/tools`，token 签发照 `ConversationControllerTest`。
  - Task 4：改 `AppRuntimeView` 前执行 `cd server && grep -rn "new AppRuntimeView(" src` 且未截断；`mvn test -Dtest=AppServiceTest,ConversationServiceTest` → 63 passed；create/update/page/get/facade/runtime 全链路带 `toolIds`。
  - Task 5：`mvn test -Dtest=ConversationServiceTest` → 33 passed；历史消息 `MessageView.toolCalls` 回显。
  - Task 6：`mvn test -Dtest=AgentChatServiceTest` → 6 passed；工具成功/失败后均推 `StreamEvent.ToolCall`，controller 映射 `event: tool_call`。
  - Task 7：`mvn test -Dtest=ConversationServiceTest` → 33 passed；`rg "AGENT_STREAM_UNSUPPORTED|17002" server/src/main/java server/src/test/java` 无残留；随后后端全量 `mvn test` → 637/0/0。
  - Task 8：文档回写 `api-standards.md` 的 `tool_call` 事件形状与 `data-model.md` 的 `app_tool_rel` V24 落地状态。
  - Task 9：`pnpm vitest run src/views/app/__tests__/AppList.spec.ts` → 22 passed；`pnpm vitest run` → 54 files / 380 tests passed。
  - Task 10：`pnpm vitest run src/composables/__tests__/useChatStream.spec.ts src/stores/__tests__/conversation.spec.ts` → 31 passed。
  - Task 11：`pnpm vitest run src/views/conversation/__tests__/ChatView.spec.ts` → 22 passed；`pnpm vitest run` → 54 files / 384 tests passed。
- 全量回归（收尾执行）：`cd server && mvn clean test` → `Tests run: 637, Failures: 0, Errors: 0, Skipped: 0`，含 `ModularityTests` 与 `LayerRulesTest`；`cd web && pnpm vitest run && pnpm build` → vitest 54 files / 384 tests passed，`vue-tsc --noEmit` 与 `vite build` 退出码 0。
- 关键红线复核：Long 继续走全局 JSON string；未新增后端错误码且删除 `AGENT_STREAM_UNSUPPORTED/17002`；新路由是成员族 `/api/v1/tool/tools`；数据库只新增 V24；DTO 未 import entity；Agent 流式采用 boundedElastic 复用同步循环，工具每次调用完成后发一次 `tool_call`，最终答案以一条 `message` delta 推出再落库。
- 真实 LLM 手验：本轮 Codex 未启动 server 做真实 LLM 黄金路径手验。自动化已覆盖流式编排、tool_call 事件、落库回显与前端渲染；仍需在有可用 provider/model、可登录 member、server 与 sandbox/外网工具连通的环境中补跑：创建/编辑 Agent 应用勾 `http_request`/`code_executor` → 试聊 → 观察 `tool_call` 卡片实时出现 → 刷新历史仍带轨迹 → 关闭 Agent 开关回归普通聊天。
- 终审补验（2026-07-14）：终审独立重跑 `mvn clean test` → 637/0/0（含 Modularity/LayerRules）、`pnpm vitest run` → 384 passed、`pnpm build` 退出 0；起 server 连 `hify` 库确认 **V24 迁移已应用**（`app_tool_rel` 建成、flyway v24 success）。真实 LLM 黄金路径由用户在 UI 手验通过：配 Agent 应用勾 `http_request`（模型 deepseek）→ 试聊外呼问题 → `tool_call` 卡片实时出现、答出结果 → 刷新历史仍带轨迹 → 关开关回归普通聊天，均 ✅（`code_executor` 因本地 server 跑在 compose 外、够不到沙箱未走此路，属拓扑非代码问题，见 [[workflow-code-node-merged]]）。
- 终审后 UI 优化（`acc3255`）：① 启用工具下拉 label 只显工具名、用法说明转 hover 原生 tooltip；② 工具调用/参考来源折叠头补左内边距 + 清箭头右外边距，左右图标各距边 8px 对称。`pnpm build` 绿。

## 2026-07-14 Agent Tool T3a（OpenAPI 自定义工具后端）

- 本轮范围（后端半）：`ApiKeyCipher` 提升为 infra 共享 `SecretCipher` 并迁移 provider；引入已批准 `swagger-parser-v3:2.1.22`；新增 OpenAPI 解析、`OpenApiToolSpec` jsonb 映射、`OpenApiToolCallback`（仅经 `OutboundHttpClient` 出站）；`ToolRegistry` 支持一条 openapi 工具展开为 N 个 Spring AI callback；新增 admin CRUD 服务与 `/api/v1/admin/tool/tools` controller（PUT 全量替换，POST 子资源启停，DELETE 返回 `Result<Void>`）。零 Flyway 迁移，复用 V23 `tool.spec/owner_id`。
- 逐 Task 验证：Task 1 provider/crypto 迁移测试 26 passed；Task 2 parser 5 passed；Task 3 spec TypeHandler 1 passed；Task 4 callback 4 passed；Task 5 registry openapi 分支 6 passed；Task 6 admin service 7 passed；Task 7 admin controller 3 passed。Mockito inline mock maker 在普通工具沙箱下无法 self-attach，相关 Maven 命令按权限规则提升后通过。
- 全量回归：`mvn -q -f server clean test` 退出码 0，surefire 聚合 **658 tests, 0 failures, 0 errors, 0 skipped**（104 份报告）。其中 `ModularityTests` 1/0/0/0、`LayerRulesTest` 5/0/0/0；tool 仍无 provider 依赖，DTO 不 import entity，controller 不注入 Mapper。
- 红线复核：加密默认值字符串与 `hify.provider.crypto` 前缀未改；本轮只新增 `ToolError.SPEC_PARSE_FAILED(13001/400)`，CRUD 复用 `CommonError`；自定义工具凭据只存 `valueEnc`，详情只回 `authHeaderNames`，不回明文；未新增 HTTP 客户端；未新增/修改 Flyway 迁移。
- 留账：OpenAPI `servers[0].url` 只支持绝对 `http/https`，相对 baseUrl 不支持；SSRF 内网地址由 `OutboundHttpClient` 统一拦截；真实 server 冒烟未执行（Task 8 Step 2 可选，当前只做自动化全量回归）。T3b 可依赖的详情契约：`ToolAdminDetailResponse` 回 `baseUrl`、`operations`、`authHeaderNames`、`rawSpec`，其中鉴权值永不回传。

### 终审补验（2026-07-14，独立复跑）

- 逐 Task 代码审查：8 提交全部忠实落地 plan。重点核实——`SecretCipher` 与原 `ApiKeyCipher` 逐字节等价、provider 两处注入点仅改类型名（`ChatClientFactory`/`ProviderService`）、`ProviderCryptoProperties` 已删、`ProviderConfig` 干净移除 `@EnableConfigurationProperties`、`CryptoProperties` 前缀保留 `hify.provider.crypto` 且 `@Component` 自注册、加密主密钥默认值一字未改；`getToolCallbacks` 去 source 过滤 + openapi 读时展开（T2 per-app 选择与 Agent 编排零改动生效）；admin 守卫齐（builtin 拒删改→10001、重名→10006、不存在→10005）。
- 独立重跑 `mvn -f server clean test`：**658 tests / 0 failures / 0 errors / 0 skipped**（104 报告），`ModularityTests` 1/0、`LayerRulesTest` 5/0——与 Codex 自检一字不差复现。
- 终审补一处功能小缺口（`46e0b0c`）：`OpenApiToolCallback` 发带 body 请求时默认补 `Content-Type: application/json`（大小写不敏感，admin 已提供则不覆盖）——真实 API 收无 Content-Type 的 JSON body 常 415/误解析。补 2 个测试，补后全量 clean test **660/0/0/0**。
- 坑记录：**不带 `clean` 的定向 `-Dtest=` 跑**会让 forked JVM 出现 `NoClassDefFoundError: io.swagger.v3.oas.models.OpenAPI`（只砸中需 swagger 类的 `OpenApiSpecParserTest`/`ToolAdminServiceTest`，12 errors + dump + MojoFailureException）；`mvn clean test` 全量则稳过。判定 tool 结果务必用 `clean` 全量，别信非 clean 定向跑。
- 未做：真实 server 冒烟（T3a 无 UI，自然并入 T3b 用管理台手验注册→列表→详情不回明文，跟 T2 同路子）；`tool.spec` jsonb 走真实 Postgres 读写往返未测（Testcontainers 按约定推迟，照搬生产已验证的 `AppConfigTypeHandler` 模式，风险低）。

## 2026-07-15 Agent Tool T3b（OpenAPI 自定义工具 admin 页）

- 本轮范围：后端新增 `POST /api/v1/admin/tool/tools/preview` 预览接口（只解析不落库）；`update` 鉴权头值留空按头名保留旧密文、create 空值显式拒绝；前端新增 admin 自定义工具页 `/admin/tool`，补类型/API 层、列表、内置只读、注册/编辑抽屉、OpenAPI 预览、鉴权头名回填且值留空。
- 后端测试：Task 1 定向先红后绿；Task 2 定向先红后绿；终审 `cd server && mvn clean test` → **666 tests, 0 failures, 0 errors, 0 skipped**，`BUILD SUCCESS`，含 `ModularityTests` / `LayerRulesTest`，Flyway Testcontainers 跑到 v24。
- 前端测试：`cd web && pnpm vitest run src/views/admin/tool` → 5 passed；终审 `cd web && pnpm vitest run` → **55 files / 389 tests passed**；`cd web && pnpm build` → `vue-tsc --noEmit && vite build` 成功（仅 Rollup 对第三方 PURE 注释与 chunk size warning）。
- 服务启动：`mvn -DskipTests package` 重打包成功；已启动后端新 jar，`GET /actuator/health` → `{"status":"UP"}`，`GET /api/v1/health` → `{"code":200,...,"data":"Hify is running"}`；已启动 Vite dev，`GET /` → HTTP 200 HTML。
- 遇到的坑：普通工具沙箱下 Mockito/Byte Buddy self-attach 失败，Maven 测试按权限规则提升后通过；MyBatis-Plus `BaseMapper.insert` 存在集合重载，测试里的 `insert(any())` 需收窄为 `insert(any(Tool.class))`；happy-dom 下 Element Plus `el-input` / `el-textarea` 会把 `data-test` 下发到真实 input/textarea，测试改为直接断言 input value；`el-drawer` 测试用透传桩绕开 teleport。
- 待人工验收：浏览器登录 admin → 侧边「管理控制台 / 自定义工具」可进入，列表含内置工具且只读；注册公网 OpenAPI 工具（禁内网/元数据地址）→ 预览操作列表 → 填鉴权头保存 → 列表出现；编辑该工具 → 名称/描述/spec 回填、只显示头名、值框为空且占位「留空=不改」→ 不填头值保存不报错；抽屉内不出现任何明文头值；到 Agent 应用配置页勾选该工具并试聊 → 看到 `tool_call` 轨迹卡片；停用后 Agent 侧不再可选；删除二次确认后列表消失。

## 2026-07-15 Agent Tool T4a（MCP 工具接入·后端）

- 本轮范围（后端半，② Agent/tool 最后子轮）：引入 `io.modelcontextprotocol.sdk:mcp:0.12.1`（不引 spring-ai-mcp/starter、**Spring AI 版本不动**）；新增 `ToolSpec` 多态接口承载 `tool.spec` 一列两形状（`kind` 分派）+ `ToolSpecTypeHandler` + **V25** 给存量 openapi 行补 `kind`；新增 `McpClientFactory`（SSRF/禁重定向/三重超时/拆 origin+endpoint）、`McpToolDiscoverer`（listTools 快照）、`McpToolCallback`（每次调用建/关连接，失败返文本不抛）；`ToolRegistry` 加 `source=mcp` 展开分支（热路径零网络开销）；`ToolAdminService` mcp 分支（create/update/refresh/preview）+ `assertOpenApi`→`assertNotBuiltin`；admin 增 `POST /tools/{id}/refresh`。**零加表**（废弃 data-model 的 `mcp_server` 表规划），**conversation/workflow 零改动**。前端拆 T4b。
- 8 项拍板决策：① 仅远程 HTTP、**不支持 stdio**（stdio 要在 server 容器 spawn 子进程执行第三方代码，推翻「不可信代码绝不进 server」）② 直依赖 SDK 0.12.1 + 手写 ToolCallback（Spring AI 1.0.1 锁的 SDK 0.10.0 **没有 Streamable HTTP**；starter 的 autoconfig 是 yml 驱动而我们 DB 驱动）③ 维持禁内网 ④ 沿用 Model D 零加表 ⑤ 快照+手动刷新（`getToolCallbacks` 在每条消息热路径上）⑥ 每次工具调用建/关连接 ⑦ `ToolSpec` 普通 interface + Jackson 多态（不用 sealed：无 JPMS 时实现类须同包）⑧ 拆 T4a/T4b。新错误码 `13002/400`。
- **三个 javap/反编译实测出的陷阱**（凭记忆必错，已写进计划 Global Constraints）：① transport Builder 的 `endpoint` 默认 `/mcp`、`sseEndpoint` 默认 `/sse` → 整条 URL 当 baseUri 传会拼成 `host/mcp/mcp`，**必须拆 origin+path**（同款前科：provider 双 `/v1`）；② `McpSchema.Tool.inputSchema()` 返回类型化 `JsonSchema` record 而非字符串，须 `writeValueAsString`（spec 初稿「零转换」说法已修正）；③ 序列化 spec/schema 的私有 ObjectMapper 须 `NON_NULL`（否则 `"defs":null` 塞进**发给模型**的 schema）+ 注册 `JavaTimeModule`（`discoveredAt` 是 `OffsetDateTime`，不注册直接序列化失败）。
- 逐 Task 验证（Codex 外部执行 Task 1-7）：Task 1 ToolSpec 多态+V25+T3a 适配；Task 2 SDK 依赖+McpClientFactory（4 tests）；Task 3 最小 MCP 桩+Discoverer（4 tests）；Task 4 McpToolCallback（5 tests）；Task 5 ToolRegistry mcp 分支（3 tests）；Task 6 ToolAdminService mcp 分支（7 tests）；Task 7 refresh 端点+回归+文档。新增 25 个测试。
- 全量回归（终审独立代跑 3 次）：`cd server && mvn clean test` → **692 tests, 0 failures, 0 errors, 0 skipped**，`BUILD SUCCESS`，含 `ModularityTests` / `LayerRulesTest`。与 Codex 自检数字一字不差。
- 红线复核：`git diff --stat` 中 **conversation/workflow 零改动**；`<spring-ai.version>` 未动（1.0.1）；未引 spring-ai-mcp/starter；只新增 V25，未改旧迁移；测试无删弱（既有 @Test 数量全部持平，`AdminToolControllerTest` 5→7）；代码与计划**逐字一致，零偏差**。

### 终审发现的真 bug（`74e6410`）

- **`create/update/refresh` 带 `@Transactional` 却内含 `discover` 网络 IO**，直接违反 CLAUDE.md 铁律「`@Transactional` 内禁止 LLM/外部 IO（防连接池耗尽拖垮全站）」。最坏 45s（连接 5s+握手 10s+请求 30s）攥着数据库连接，几个 admin 并发注册即可抽干池子（20 条）。**根因在 spec/plan 就埋下**（照抄 T3a 的事务注解，没意识到 discover 引入了 IO），Codex 忠实实现无过错；**692 个单测抓不到**——discoverer 被 mock，没有真实延迟。类注释「@Transactional 只在写方法，内无外部 IO」被 T4a 变成假话，一并修正。
- 修法（用户拍板方案 A）：去掉这三个方法的 `@Transactional`。安全性论证：三者各只有**一条写语句**（insert/updateById），单语句本身即原子；重名查重是建议性友好提示，真正守卫一直是 `tool_name_uq` 唯一索引（T3a spec 既定）。`delete/enable/disable` 无 IO，保留注解无害。
- 同轮删孤儿产物 `er-diagram.png`：含 `mcp_server` 残留，但**无法用 CLAUDE.md 规定的 wasm-graphviz-cli 重生成**（不支持 png 格式，本机亦无原生 graphviz），且无人引用、未登记于文档索引（索引登记的是 `er-diagram.svg`(.dot)）。svg 为唯一真相源。

### Task 8 真实公网 MCP 实测（本轮最大风险，已解除）

- **DeepWiki（`https://mcp.deepwiki.com/mcp` + `streamable_http`）连通**，发现 3 个工具（read_wiki_structure / read_wiki_contents / ask_question）。Context7 两种传输均失败（疑已需 API key）。
- **决策 2 被回溯证明救了整轮**：DeepWiki 的 **`sse` 传输失败、只有 `streamable_http` 成功**。若当初选 A（守 Spring AI 1.0.1 锁的 SDK 0.10.0，只有老 SSE），**一台服务器都连不上，T4a 会是无法验收的摆设**。
- 端到端（app 28「调用工具测试」，模型千问）：工具轨迹出现 **`deepwiki__read_wiki_structure`**（命名前缀 `sanitize(注册名)__远端工具名` 生效），拿到真实文档结构，模型答对。
- 其余实证：V25 在**真实存量数据**上验证通过（`weather` 行补上 `kind`，`baseUrl`/`operations`/`rawSpec` 无损）；内网 MCP 地址真实链路返回 **10001 而非 13002**（spec §7.1 错误码边界成立）；`refresh` 使 `discoveredAt` 真的刷新（17:41:43→17:42:46）；`refresh` 作用于 openapi 行 → 10001「只有 MCP 工具支持刷新」；builtin 删除 → 10001「内置工具不可删除」。
- **MCP 调用 ~25% 失败率（根因外部，非 bug）**：Hify 侧 8 次 preview 失败 2 次；**裸 curl 直连 DeepWiki 10 次失败 3 次（HTTP 000，连接层失败）**，且 10 次 `redirect_url` 全空 ⇒ **`followRedirects(NEVER)` 清白**，是 DeepWiki 服务端/网络链路问题。日志根因：`Error deserializing JSON-RPC message: AggregateResponseEvent[..., data=]`（空响应体）。**失败契约在真实故障下经受住考验**：第 1 次 MCP 调用失败 → 返回错误文本 → Agent 循环未断 → 模型自行重试第 4 次成功。

### 计划质量教训（本轮 Codex 4 次叫停，4 次都是计划 bug）

- **根因同一类：Task 边界切在了「编译原子单元」中间**。① Task 1 类型迁移（`Tool.getSpec()` 返回类型一改，ToolAdminService 4 处 / ToolRegistry 1 处 / ToolAdminServiceTest 4 处**同时**编译不过，中间不存在可编译快照）→ 修法是把「跑测试转绿」整体后移到全部适配之后；② Task 5 漏列 `ToolRegistryTest`/`ToolFacadeImplTest` 两处构造点；③ Task 6 `preview(String)`→`preview(PreviewToolRequest)` 但 `AdminToolController` 只在 Task 7 的 Files；④ Task 7 Step 8 要改 `er-diagram.dot/svg` 却未列入 Files。
- **grep 模式本身会静默漏**：写计划时用 `new UpdateToolRequest(` 搜不到任何东西——代码里是**全限定名** `new com.hify.tool.dto.UpdateToolRequest(`（3 处），而我把这条带 `new ` 前缀的 grep 命令**原样写进了计划给执行方**，即使照做也会漏。同因漏掉整份 `AdminToolControllerTest`。⇒ 枚举调用点**用类名本身搜，别带 `new ` 前缀**。
- **明确否决的反模式**：Codex 曾提议「Task 6 临时保留 `preview(String)` 兼容重载，Task 7 再切」——这是 W3a 那轮踩过的坑（为迁就人为 Task 边界造用完即删的垫片，产出语义危险的兼容层）。**边界划错了就改边界，不加垫片。**
- **红线设计生效**：4 次叫停全部发生在「计划红线与计划自身疏漏冲突」时，Codex 每次都停下报告而非自作主张绕过 ⇒ 漏洞在**写代码之前**暴露，而非变成潜伏的垫片代码。代价是 4 个来回，划算。
- **C3 的结构对策部分失效**：把回归/文档并入 Task 7 收尾 steps 后，Codex 仍未提交 Task 7（停在 Step 8 叫停处，Step 1-7 已做但未 commit）。结论：「并入最后功能 Task」能防跳过 step，但**叫停发生在收尾 Task 时仍会留下未提交状态**，终审须默认检查工作区（`git status`）而非只看 commit。

### 留账

- **MCP 调用 ~25% 失败率**：根因外部（DeepWiki/网络链路），优雅降级已验证有效。是否加 Resilience4j 重试（项目已有依赖，llm-resilience.md 有先例，可把 25% 压到 ~2-6%）待用户拍板，建议独立小轮次，未纳入 T4a。
- **「禁内网」的战略后果（决策 3 的未预期代价）**：团队真正会用的自建 MCP 服务器全在内网被禁；剩下可用的只有公网免费服务，而它们从本网络环境访问本就不稳。安全上正确，但可能把 MCP 推向可用性最差的角落。**T4b 之前值得重议**：这功能实际要给谁用、连什么。
- `V23` 迁移注释「mcp_server(T4) 另轮建表」现已是假话，但**按铁律不改**（改动会破坏 Flyway 校验和直接炸启动）；`data-model.md` §4「刻意不存在的表」已新增 `mcp_server` 条目记录废弃理由，防将来重提。
- 其他既定非目标：MCP 的 resources/prompts 不接、OAuth 授权流不做、多模态工具结果给占位、per-app 只能勾整个 MCP 服务器（与 OpenAPI 工具一致）、DNS rebinding 窗口（与 `OutboundHttpClient` 现状一致，非新增）。
- 验收残留数据（未清理）：`tool` 表 id=4 `deepwiki`（source=mcp）；app 28「调用工具测试」模型由 deepseek 改为千问（deepseek 供应商报 12003 不可达）、toolIds 由 `["1"]` 改为 `["1","4"]`。
- **T4b（前端）未开始**：`web/` 本轮零改动，无 T4b spec/plan。admin 工具页（T3b 建）目前只认 OpenAPI，MCP 注册只能 curl；但 Agent 配置页与对话页**零改动即可用 MCP 工具**（本轮设计要证明的透明性，已实证）。

## 2026-07-16 Agent Tool T4b（MCP 内网白名单 + MCP admin 注册页）——② Agent/tool 方向收官

spec `docs/superpowers/specs/2026-07-15-agent-tool-t4b-mcp-admin-ui-design.md`、plan 同名 plans/；
Codex 执行 5 Task（`ff94322..80e2702`）+ 终审修缮 2 commit（`70051cd`、`8e23ab7`）。
人工验收：线 1 DeepWiki 公网全流程过；线 2 白名单核心断言过（详见下），完整闭环待自建 MCP 就绪补验。

### 范围与结论

- **后端（一个 Task）**：`hify.tool.mcp.allowed-private-hosts` yml 白名单（精确 host、忽略大小写、默认空=行为不变），
  `McpClientFactory` 命中即跳过 `SsrfValidator`；**仅 MCP 出站生效**——威胁模型按「URL 由谁控制」分层：
  MCP 地址仅 admin 注册（受信），HTTP 节点 URL 成员可填、内置 HTTP 工具 URL 模型可选（提示注入可操纵），后两类维持禁内网。
  原「SsrfValidator 查 system_setting」预留被否：infra 只依赖 common、system_setting 属 provider、tool 依赖白名单为「无」，
  打穿模块边界不值；MCP 出站闸门本就收口在 tool 模块 `McpProperties`，放这里零边界问题、零新 API、零新表。
- **前端**：T3b 抽屉先拆成 `ToolDrawer.vue`（318 行加 MCP 必超 ~300 行规范线）再加 MCP 分支——
  类型 radio（编辑态只读 el-tag，沿 ProviderDetail 先例绕开 EP radio disabled 覆盖坑）、url/transport/鉴权头、
  试连接（仅新建；编辑态鉴权头值空试连必假失败→改展示快照+discoveredAt）、列表三类标签+刷新按钮+列名「操作/工具数」。
- 回归：`mvn clean test` 696/0/0/0、`pnpm vitest run` 403/403（终审后含修复新增 7 用例）、`pnpm build` 过。
- **Codex 执行零偏差**：5 commit 与计划逐字一致，红线例外（计划勾选）无内容篡改——T4a 的 4 次叫停教训
  （原子边界+全量 grep 调用点）在本轮计划里逐条预防，一次没叫停。

### 验收实测抓出的双故障（`8e23ab7`，测试先红后绿）

- **「点保存无反应」= 10001 字段错误的静默约定咬人**：描述留空 → 后端 `@NotBlank` 返回 10001+字段数组 →
  拦截器按约定不弹全局 toast（留给表单逐项标红），抽屉 catch 又吞掉 ⇒ 界面零反应。curl 复现实锤。
  修法：前端预检补「请输入描述」+ catch 里 `toastFieldErrors` 兜底（未做逐项标红的表单必须兜底，否则校验失败=静默）。
  **同类隐患早有留账**（K2「上传报 10001 前端静默」），这是第二次咬人——凡用 el-form 之外的手搓表单都要自查这条。
- **「网络异常」= D2 坑第二次踩（前端超时 < 后端预算）**：MCP create/update/preview/refresh 在服务端现场
  连远端发现工具，最坏预算 connect 5s+init 10s+listTools 30s ≈ 45s > axios 默认 30s ⇒ 远端一慢客户端先断。
  修法：四个发现类接口带 `config.mcpDiscoverTimeoutMs=60_000`（沿 `llmTestTimeoutMs` 既有模式）。
  **模式化教训：任何「同步接口内含现场外联」上线前，必须核对前端超时 ≥ 后端全链路预算**——
  T4a 终审抓的是它的事务版（@Transactional 内禁 IO），本轮抓的是超时版，同一个根源（discover 是 IO）两副面孔。

### 线 2 验收期间的「网络异常」误报排障（结论：环境瞬断，非 bug）

- 现象：试连接 `http://127.0.0.1:8931/mcp` 前两次正确报 13002，之后连点全报「网络异常」。
- 取证：后端连打 5 次 preview 全 0.02s 返 13002（无劣化）；走 5173 代理同款路径亦 0.02s 正常；
  读 Vite 6.4.3 源码证明后端挂时代理回 500 空体（前端文案会是「服务器繁忙」而非「网络异常」）⇒
  「网络异常」只能是浏览器连 5173 那跳断了；vite 进程 14:44:32 才启动 = 用户重启后端时把 dev server 一并断了，
  旧页面每次点击瞬间 ERR_NETWORK。刷新页面后恢复。
- **排障口诀入档：「服务器繁忙」=后端 5xx/代理 500；「网络异常」=浏览器根本没拿到响应（dev server 挂/超时/断网）——
  两条文案分流是 request.ts 设计好的，报障时据此直接定位层级。**
- **白名单验收的铁证口径：10001→13002 的转变即放行成功**（10001=SSRF 闸门拒绝没出网；13002=已出网但对端无服务）。

### 留账

- 线 2 完整闭环（内网注册成功→Agent 调用）待自建 MCP 服务器就绪后补验；机制本身已证。
- 验收残留：tool 表 id=4 `deepwiki`（T4a 留）+ 线 1 新注册的 MCP 行；复现行 id=5 已删。
- ② Agent/tool 四子轮（T1/T2/T3a+b/T4a+b）全部完结；① 对外 API 触发维持推迟（等真实调用方）。

## 2026-07-16 mcp-demo Task 1 自检

- 范围：工程脚手架 + `get_current_time` 纯函数。
- TDD 红灯：`pnpm test` 失败，报 `Cannot find module '../current-time.js'`，符合计划预期。
- 通过证据：`pnpm test && pnpm typecheck` 通过；vitest 汇总行 `Test Files  1 passed (1)`；typecheck 退出码 0。
- 偏差：`pnpm init` 生成的 `devEngines.packageManager.version: "^11.4.0"` 会让后续 `pnpm add` 报 semver 错，先按计划目标形状修正 `package.json` 后继续；pnpm 11 生成 `pnpm-workspace.yaml` 的 `allowBuilds.esbuild` 占位，需设为 `true` 才能运行 vitest 的 esbuild postinstall。

## 2026-07-16 mcp-demo Task 2 自检

- 范围：`roll_dice` 纯函数。
- TDD 红灯：`pnpm test` 失败，报 `Cannot find module '../roll-dice.js'`，符合计划预期。
- 通过证据：`pnpm test && pnpm typecheck` 通过；vitest 汇总行 `Test Files  2 passed (2)`；typecheck 退出码 0。
- 偏差：无新增偏差。

## 2026-07-16 mcp-demo Task 3 自检

- 范围：`createMcpServer` 工厂 + InMemory 协议集成测试。
- TDD 红灯：`pnpm test` 失败，报 `Cannot find module '../server.js'`，符合计划预期。
- 通过证据：`pnpm test && pnpm typecheck` 通过；vitest 汇总行 `Test Files  3 passed (3)`；typecheck 退出码 0。
- 偏差：计划测试期望 zod 非法参数导致 `client.callTool` reject；实际 `@modelcontextprotocol/sdk@1.29.0` 返回 `isError: true` 工具结果。最小修正该用例为断言 `result.isError === true`，实现代码未偏离计划。

## 2026-07-16 mcp-demo Task 4 自检

- 范围：Express app 鉴权 + `/mcp` 无状态路由 + 入口 + README + `CLAUDE.md` 布局收录。
- TDD 红灯：`pnpm test` 失败，报 `Cannot find module '../app.js'`，符合计划预期。
- 通过证据：Step 4 `pnpm test && pnpm typecheck` 通过，vitest 汇总行 `Test Files  4 passed (4)`，typecheck 退出码 0；Step 9 全量回归同样通过，vitest 汇总行 `Test Files  4 passed (4)`，typecheck 退出码 0。
- curl 冒烟：`pnpm dev` 打印 `mcp-demo listening on http://localhost:3100/mcp`；curl initialize 返回 SSE，含 `"serverInfo":{"name":"mcp-demo","version":"1.0.0"}`；冒烟后已停止后台进程。
- 偏差：sandbox 内 curl 访问放权启动的 localhost 进程返回 exit 7，改用同一放权上下文重跑 curl 后通过；代码和请求内容未偏离计划。

## 2026-07-16 mcp-demo Task 5 人工验收（本轮收官）

- 终审（Task 1-4，Codex 执行）：25/25 测试+typecheck 亲跑复验通过；4 处偏差全部核实合理，其中「zod 校验失败预期」是计划写错——SDK 1.29 的 `CallToolRequestSchema` catch 块把 InvalidParams 统一转 `isError:true` 工具结果（源码实证），Codex 修正正确。
- 白名单变量传递勘误：根目录无 `.env` 且 `start.sh` 不加载 `.env`（那是 compose 的机制）；本地跑法用命令前缀 `HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS=localhost make restart`，经 `/proc/<pid>/environ` 实证变量已进 Java 进程。spec §8 与 mcp-demo/README 已勘误。
- 验收中排障一例（13002「Client failed to initialize by explicit API call」）：
  - 判别：报 13002 而非 10001 → 白名单已放行；backend.log 有 `Server response ... Implementation[name=mcp-demo]` → 握手已成功，挂在后续消息。
  - 根因（证据链齐）：TS SDK 按规范给纯通知回 `new Response(null, 202)`（webStandardStreamableHttp.js:462）→ SDK 1.29 Node 适配层换 Hono，`buildOutgoingHttpHeaders` 对空头强补 `content-type: text/plain`（@hono/node-server index.mjs:434）→ Java MCP 客户端 POST 响应只认 json/event-stream/无头三种，见 text/plain 抛 Unknown media type，initialize 生命周期中断。两官方 SDK 互操作 bug，与业务代码无关。
  - 修复：`app.ts` 写响应头前对 202 剥 content-type（TDD，回归测试锚定 202 无 content-type），d920dc5。
- 验收结果（人工确认）：注册试连接发现 2 工具 ✓；错 token 反向验证报 13002 ✓；聊天「现在几点+掷骰子」Agent 两次真调用、轨迹可见 ✓。
- T4b 留账「线 2 完整闭环（内网注册成功→Agent 调用）待自建 MCP 服务器就绪后补验」：就此销账。

## 2026-07-17 部署容器化收尾自检（Task 1-4）

- 范围：deployment.md「单机 4 容器」落地——server/nginx 多阶段镜像、compose profiles 双形态、三网拓扑、自签 TLS、优雅停机配置就位。
- 通过证据：`docker compose --profile app ps` 4 容器全部 healthy；冒烟 http→301 https、SPA 壳与深层路由 200、经 nginx 反代登录返回 JWT（code 200）；双形态回归——`docker compose up -d` 恰好 2 容器（postgres/sandbox），`--profile app` 4 容器；后端 `mvn verify` 退出码 0、前端 vitest 56 文件/403 用例全过。
- 偏差（4 处，均已回写计划/spec）：① 容器内出网不走宿主机代理（fake-IP DNS），Maven 依赖改走阿里云镜像（新增 server/maven-settings.xml）；② nginx 镜像 COPY 补 web/.npmrc 与 web/pnpm-workspace.yaml（pnpm11 的 allowBuilds 白名单缺失会 ERR_PNPM_IGNORED_BUILDS 退出非零）；③ Task1 Step4 冒烟命令补 --entrypoint（原命令追加参数被 ENTRYPOINT 的 sh -c 忽略）；④ 登录成功码实际为 code 200（计划笔误写 0）。
- 已知边界：优雅关闭「等待 workflow 收尾」行为验证留第 2 轮；生产 VM 需删 postgres 端口发布、换真证书、改 .env 默认值；deploy/.env 受本地权限保护，由用户手动创建。
- 待人工验收：浏览器黄金旅程 6 条（登录/SSE 流式/传文档+引用/代码节点工作流/深层路由刷新/down-up 数据保留）。

### 2026-07-17 补记：验收项 2「SSE 流式」排障结论

- 现象：浏览器测试"卡顿后一次全出"。
- 定位（对照实验）：经 nginx 与容器内直连 server 表现一致（排除 nginx）；克隆无工具应用仍爆发；关掉 `agentEnabled` 后经 nginx 完美逐字流式（每 50-100ms 一个 delta）。
- 结论：**nginx SSE 配置正常**。整段爆发是 Agent 应用（agentEnabled=true）的既定行为——T2 轮方案一拍板"Agent 循环非流式，最终答案一次发出"；库中 3 个聊天应用恰好全开了 Agent，宿主机形态行为相同，非本轮容器化回归。
- 验收口径修正：验收项 2 需用「未开 Agent 的纯聊天应用」测试。

## 2026-07-17 运维补账 Task 1-2：备份与恢复演练

- 备份成功路径：`pg-backup.sh` 退出码 0；产物 `hify-20260717-114304.dump`，实测 1,436,140 bytes（`du -h` 为 1.4M）；目录内 `.dump` 1 个、`.tmp` 0 个。
- 备份失败路径：指定 `HIFY_PG_CONTAINER=no-such-container` 后 stderr 原文为 `[pg-backup] 2026-07-17 11:43:19 容器 no-such-container 未运行，备份中止`，退出码 1，目录文件数执行前后均为 1。
- 恢复：临时 `pgvector/pgvector:pg16` 容器就绪后，`pg_restore --no-owner --no-privileges` 退出码 0，无 WARNING/ERROR。
- 五项核对（恢复库 / 源库）：public 表数量 `32 / 32`；Flyway 成功迁移数与 `max(version)` 为 `25|9 / 25|9`；`sys_user` 行数 `5 / 5`；非空向量数 `208 / 208`，恢复库相似度查询返回 id `140`、`124`、`156`；两张日志表的分区子表总数 `12 / 12`。
- README 演练步骤出入：无；临时容器及匿名卷已销毁。

## 2026-07-17 运维补账 Task 3：分区补建实证（抓出并修复真 bug）

- 实验一（修复前，Codex 执行，容器 JVM=UTC）：确认 `llm_call_log_2026_10`/`workflow_node_run_2026_10` 均为空（`0|0`）后 drop → 重启 → **未重建**，server 进重启循环（实测 restarts=63，后升至 63+）；关键错误原文：`ERROR: partition "llm_call_log_2026_10" would overlap partition "llm_call_log_2026_11"`。
- 根因（证据链）：存量分区边界全为 `16:00:00+00`（= 北京时间零点，V12/V21 由宿主机 +08 会话建出）；容器 `TZ=unset` 实测 UTC，Maintainer 的裸日期字面量 `'2026-10-01'` 被按 UTC 解释，与 +08 边界错位 8 小时 → 重叠被拒 → ApplicationReadyEvent 监听器抛异常拖垮启动。
- 若不做本实验的自然引爆点：2026-10-01 Maintainer 首次建全新分区 `2027_01`（UTC 边界）会**静默成功**，但与 `2026_12`（+08 边界）之间留 8 小时缝——2027-01-01 北京时间 0:00-8:00 两张日志表写入全部失败（工作流执行失败、token 流水丢失）。
- 现场处置：手工按存量约定补建两个分区（`from '2026-10-01 00:00:00+08'`）救活服务（healthy，数据零丢失）。
- 修复（用户拍板方案三，commit aa923e3）：① 两个 Maintainer 边界显式带 `+08:00` 偏移（TDD：先红后绿 4 用例，锚定格式与跨年）；② compose 给 server 加 `TZ=Asia/Shanghai` 护住 Flyway 首跑建库的旧脚本裸字面量（旧迁移禁改）。`mvn verify` 700 用例全过（PIPESTATUS 判定，退出码 0）。
- 实验二（修复后，重建镜像）：同法 drop → 重启 → healthy 不崩；日志 `llm_call_log 分区已确保至 2026-10` / `workflow_node_run 分区已确保至 2026-10`（时间戳已是北京时间）；重建边界实测 `FROM ('2026-09-30 16:00:00+00') TO ('2026-10-31 16:00:00+00')`，与存量完全对齐。**补建机制就此真正实证。**
- 附带发现（记录不改）：Maintainer 启动失败会 fail-fast 拖垮整个 server——可接受，缺分区继续跑写入照样失败，不如早死早报警。

## 2026-07-17 运维补账 Task 4：优雅停机正反实证（抓出并修复第二个真 bug）

- 首跑正向即触发刹车：工作流虽跑完（响应 succeeded、库中终态），但 `docker compose stop` 实测 **60.5s** = stop_grace_period 特征，且日志**无** `Commencing graceful shutdown`——SIGTERM 从未到达 JVM。根因：`server/Dockerfile` 的 `ENTRYPOINT ["sh","-c","java ..."]` 让 sh 任 PID 1 且不转发信号，优雅停机三件套在容器形态全部失效，此前每次 stop/发布实为 60s 后 SIGKILL。
- 修复（用户拍板，commit 719fbc1）：ENTRYPOINT 加 `exec`，java 替换 sh 成为 PID 1 直收 SIGTERM；重建后实测 `/proc` PID 1 = java。
- 正向场景（单代码节点 sleep(20)，t=3s 发 SIGTERM）：stop 实测 **18.4s**（等收尾即退，非傻等 50s）；日志 `Commencing graceful shutdown ... Waiting for active requests` → 17s 后 `Graceful shutdown complete`；响应完整送达（status=succeeded, elapsedMs=20179）；库 run 44=succeeded；重启后无自愈日志（0 僵尸，预期）。
- 反向场景（3×sleep(25)≈75s，t=5s 发 SIGTERM）：stop 实测 **53.5s** ≈ 50s Spring 预算+收尾（未触及 docker 60s SIGKILL，层次正确）；库 run 45 留 `running` 僵尸；重启后日志 `workflow 启动自愈：重置 2 条遗留 running 记录为 failed`（恰 1 run + 1 node_run，node_run 错误文案「服务重启中断」）；run 45 终态 failed。
- 结论：**SIGTERM 后等待 workflow 收尾 + 超时强杀由启动自愈兜底，整条链路实证完整**（且实证了 50s<60s 的层次设计确实生效）。
- 实验环境恢复证据：沙箱超时实验变量（经 scratchpad 临时 override 注入，仓库零改动）撤除后 `docker exec hify-server env | grep HIFY_SANDBOX` 为空；两个实验应用已删（code 200）；临时文件已清理。
