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
