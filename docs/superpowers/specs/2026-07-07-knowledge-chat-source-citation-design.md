# 聊天引用来源展示 — 设计文档

> 日期：2026-07-07
> 模块：`conversation`（主角，前后端一体）+ `knowledge`（零改动，仅消费其 `RetrievedChunk`）
> 前置：K4（检索接入对话）已合并 main 并验收（main=e22ba87 起）。K4 spec 把本轮定性为「引用来源展示（后续小轮，需动 message 表+SSE 协议）」，本轮兑现。
> 范围口径：**把 K4 检索到但当前被丢弃的命中段，做成「分段级来源快照」随 assistant 消息落库（message 加 jsonb 列），并经流式/同步/重载三条路径统一喂给前端，在气泡下方用可折叠的「参考来源」卡片展示（纯展示，不可点）**。
> 明确**不做**：来源卡片点击/跳转文档详情/就地展开全文（看全文走已有命中测试弹窗）、检索/embedding Token 计量、Rerank/混合检索（二期）、来源反查（哪些回答引用了某文档）、命中测试弹窗改动、K4 及更早留账项（继续留账）。

---

## 0. 决策摘要（brainstorm 拍板结果）

| # | 决策点 | 结论 | 理由 |
|---|---|---|---|
| 1 | 展示粒度 | **分段级 + 短预览**：每命中段存 `chunkId/documentId/documentName/score/preview（content 前 N 字快照）`，**不存整段全文** | 分段级比文档级有信息量（用户判断答案可信度要看命中哪段、分数多高）；不存全文避免 message 历史随会话滚动膨胀；看全文有命中测试弹窗兜底 |
| 2 | 存储形态 | **message 表加 `sources jsonb` 列**，不建关联表 | 来源是消息附属品、永远同生共死、无反查需求；照抄 `tool_calls jsonb` / `AppConfig` 同款；建关联表为不存在的反查需求付 join 代价，违 YAGNI |
| 3 | SSE 传递 | **新增 `sources` 事件**（Meta 后、Delta 前发），**不改** Done/Meta/Delta | 守 K4/修缮轮「SSE 事件只增不改」铁律；检索在流建立前就绪，来源可与 Meta 一样早发；老前端遇未知事件忽略、零破坏 |
| 4 | 前端形态 | **气泡下方「📎 参考来源 (N)」折叠区**，默认收起，展开见卡片 | 4 条来源常驻会挤占聊天区；折叠默认不打扰阅读，又随手可查 |
| 5 | 交互边界 | **纯展示**：不可点、不跳转、不拉全文 | 本轮是「让用户看见依据」的轻量小轮；跳转要碰跨视图路由+文档级权限+坏链兜底，全文与决策 1「不存全文」矛盾，均超范围 |

---

## 1. 数据形状（唯一贯穿三路径的 DTO）

新建 `com.hify.conversation.dto.MessageSource`（成员族响应 DTO，纯 record，**不 import entity**）：

```java
/** 引用来源快照（一条命中段）。三路径共用：MessageView.sources / SSE sources 事件 / message.sources jsonb。 */
public record MessageSource(
        Long chunkId,        // 全局 Jackson → string
        Long documentId,     // → string
        String documentName, // 答题那一刻的文档名快照（文档后续改名/删除不影响历史留痕）
        double score,        // 相似度 0~1，保持 number（Long→string 规则不影响 double）
        String preview) {}   // content 截前 N 字的快照，不含整段全文
```

**同一个 DTO 同时用于三处**：`MessageView.sources`（同步返回体 + 重载历史）、SSE `sources` 事件载荷、message 行 jsonb 内容。前端只需认识一种数据形状。

**跨模块映射**：conversation 在 service 里把 `com.hify.knowledge.api.RetrievedChunk`（`chunkId/documentId/documentName/content/score`，content 为全文）→ `MessageSource`：
- `preview = 截断(RetrievedChunk.content(), previewLength)`，**截断动作在 conversation 侧**——「不存全文」由 conversation 自己掌控，knowledge 零改动。
- 其余字段直传。

conversation 已依赖 `knowledge.api`（K4 起），**无新增跨模块依赖**。

## 2. 数据库：V20（conversation 模块）

```sql
-- V20：message 加来源快照列（conversation 模块）。绑库应用回答的引用依据，随消息一起读/删（会话删级联）。
-- 只新增列，不改 V10；default '[]' 保证存量历史消息读出为空数组，前端不渲染卡片。
alter table message add column sources jsonb not null default '[]';
comment on column message.sources is '引用来源快照数组 [{chunkId,documentId,documentName,score,preview}]；未绑库/降级/无命中恒为 []';
```

- 无新索引（决策 2：不反查）。
- 下一个可用版本号为 **V20**（现有最新 V19）。

## 3. 持久化（Message 实体 + TypeHandler，照抄 AppConfig 套路）

项目现成 jsonb 范式：`com.hify.app.config.AppConfigTypeHandler`（`BaseTypeHandler` → 写包 `PGobject(type=jsonb)`、读空值兜底）配 `@TableName(autoResultMap=true)` + `@TableField(typeHandler=...)`。照抄：

- `Message` 实体加字段 `List<MessageSource> sources`：
  - 类注解 `@TableName(value = "message", autoResultMap = true)`。
  - 字段注解 `@TableField(typeHandler = MessageSourcesTypeHandler.class)`。
- 新建 `com.hify.conversation.config.MessageSourcesTypeHandler extends BaseTypeHandler<List<MessageSource>>`：
  - `setNonNullParameter`：`PGobject(jsonb)` + `MAPPER.writeValueAsString(list)`。
  - `getNullableResult`：空/空白 JSON → `List.of()`（保证非 null）；否则 `MAPPER.readValue(json, new TypeReference<List<MessageSource>>(){})`。
- `ConversationStore.appendAssistant(...)` 签名加 `List<MessageSource> sources` 参数，落库时写入实体。
- `tool_calls` 保持现状（本轮不映射，DB 默认 `[]`）——只加 `sources`，不动 `tool_calls`。

## 4. conversation 编排改动（核心）

`ConversationService.augmentWithKnowledge` 现返回 `String`，改为返回小 record（service 私有）：

```java
private record Augmented(String prompt, List<MessageSource> sources) {}
```

四分支（**降级天然一致**：没来源就没卡片，与 K4「检索失败继续答」现状对齐）：

| 分支 | prompt | sources |
|---|---|---|
| 未绑库（datasetIds 空） | `app.systemPrompt()` | `List.of()` |
| 检索抛异常（未配模型/供应商故障/池满）→ 降级 | `app.systemPrompt()`，log.warn | `List.of()` |
| 命中为空（阈值全滤掉） | `app.systemPrompt()` | `List.of()` |
| 命中非空 | systemPrompt + 参考资料段（**拼提示词仍用 `RetrievedChunk.content()` 全文**，不变） | 命中段映射为 `MessageSource`（preview 截断） |

**`send`（同步）**：
```
Augmented aug = augmentWithKnowledge(app, content);
LlmReply reply = chatInvoker.invoke(chatClient, aug.prompt(), turn.window());
Message saved = store.appendAssistant(cid, ..., aug.sources());
return new SendMessageResponse(cid, toView(saved));   // toView 带出 sources
```

**`sendStream`（流式）**：`Augmented` 在流建立前（现第 96 行位置）已就绪 →
```
Flux.concat(
    Mono.just(new StreamEvent.Meta(cid)),
    aug.sources().isEmpty() ? Flux.empty() : Flux.just(new StreamEvent.Sources(aug.sources())),  // 空不发
    deltas,
    done   // done 内 appendAssistant(..., aug.sources()) 落库
)
```
- **空来源不发 `sources` 事件**（省流、前端零卡片）。
- 现有 `onErrorResume` 孤儿清理逻辑**不动**：流中途 onError → cleanupFailedTurn 删消息 → 重载时无消息、无残留来源。（活动 UI 上已出现的 sources 挂在随后报错的气泡上，属可接受表现，不特殊处理。）
- 位置仍在两事务间隙，`@Transactional` 内零外部 IO 红线不变。

## 5. SSE 协议增量

- `StreamEvent` sealed 接口 `permits` 加 `Sources`：
  ```java
  /** 引用来源（决策 3）：Meta 后、首个 Delta 前发出；命中为空则不发此事件。 */
  record Sources(List<MessageSource> sources) implements StreamEvent {}
  ```
- `StreamPayloads` 加 wire 载荷 `record Sources(List<MessageSource> sources) {}`。
- Controller 映射 `StreamEvent.Sources` → SSE `event: sources` + JSON body（沿用现有事件名→payload 映射手法）。
- `MessageView`（server DTO）加 `List<MessageSource> sources`，`toView` 从实体 `getSources()` 带出。
- **Done/Meta/Delta 结构零改动**，守「只增不改」铁律。

## 6. 前端（Vue，全程 TDD/vitest）

| 文件 | 动作 |
|---|---|
| `web/src/types/conversation.ts` | 加 `MessageSource` 接口（`chunkId/documentId/documentName: string`；`score: number`；`preview: string`）；`MessageView` 加 `sources: MessageSource[]`（默认 `[]`，与既有客户端专用 `error?` 字段并存） |
| `web/src/composables/useChatStream.ts` | 解析新 `sources` 事件 → `onSources(list)` 回调（与既有 `onMeta`/`onError` 同套手法） |
| `web/src/stores/conversation.ts` | 流式/同步/重载三路径把 sources 挂到对应 assistant 消息对象 |
| `web/src/views/conversation/ChatView.vue` | assistant 气泡下、`sources.length > 0` 时渲染折叠区：`el-collapse` 标题「📎 参考来源 (N)」，展开每段一张卡：文档名 + `el-tag` 分数（`score` × 100 取整显示百分比）+ preview 文字。**纯展示不可点**（决策 5）。空来源不渲染 |

- 用 Element Plus 现成组件（`el-collapse`/`el-collapse-item`/`el-tag`/`el-icon`），遵 `prefer-element-plus-components`（规范 §5.9）。
- 折叠区默认收起（决策 4）。

## 7. 配置增量（application.yml）

```yaml
hify:
  conversation:
    source-preview-length: ${HIFY_CONVERSATION_SOURCE_PREVIEW_LENGTH:120}
```

- 仅此一键：preview 截断长度，默认 120 字（遵 CLAUDE.md「配置外化、不硬编码」）。
- 配置消费方为 conversation 的映射逻辑（`RetrievedChunk.content()` → `MessageSource.preview`）。

## 8. 测试（TDD 先红后绿）

### 8.1 后端单元/切片
- `augmentWithKnowledge` 四分支：命中→`Augmented.sources` 带 preview 截断（长度=配置值、超长截断、不足不填充）；未绑→空；retrieve 抛异常→空且 warn（降级）；命中空→空。
- SSE 编排：`sources` 事件在 `Meta` 之后、首个 `Delta` 之前；空来源**不发** sources 事件；Done 落库带 sources。
- Controller 切片：SSE 事件名为 `sources`、载荷 `chunkId/documentId` Long→string、`score` 为 number；`MessageView.sources` 序列化（同步 + 历史）。
- ModularityTests/ArchUnit 回归：`MessageSource` 在 conversation.dto、不 import entity、无新增跨模块依赖，应零修改通过。

### 8.2 连库（继承 `com.hify.support.PgIntegrationTest`）
- `sources` jsonb 写入 → `history`/`appendAssistant` 读回往返（含中文 preview、多条来源、score 精度）。
- 存量兼容：V10 建的旧消息（无 sources）经 V20 `default '[]'` 读出为空 `List.of()`，不 NPE。

### 8.3 前端 vitest
- `useChatStream`：收到 `sources` 事件 → 触发 `onSources` 且解析出数组。
- `stores/conversation`：流式/同步/重载三路径各自把 sources 挂到 assistant 消息。
- `ChatView`：`sources.length>0` 渲染折叠区（计数 N、卡片文档名/preview、分数百分比格式）；空来源不渲染；默认收起。

### 8.4 手动验收
建知识库传文档（ready）→ 应用绑库 → 聊天问文档内问题：① 流式回答过程中「参考来源 (N)」折叠区出现，展开见文档名/分数/预览；② 刷新页面重载历史，来源仍在；③ 同步端点（非流式，若前端可切）同样带来源 → 问无关问题（回答正常、无来源折叠区）→ 停用 embedding 模型后聊天（降级照常答、无来源、server 日志 warn）→ member 账号全流程同权限口径。

## 9. 不破契约（约束清单）
- 既有端点签名/错误码零改动；**错误码零新增**（无新失败模式，降级复用现状）。
- 本轮新增仅：SSE `sources` 事件（纯增量）、`MessageView.sources` 字段（新增字段向后兼容）、`message.sources` 列（V20，`default '[]'` 兼容存量）。
- SSE **只增不改**：Meta/Delta/Done 结构不动。
- 只新增 V20，不改旧迁移；`@Transactional` 内零外部 IO（检索仍在事务间隙）。
- DTO 不 import entity；`MessageSource` 放 conversation.dto（成员族 DTO）；跨模块仍只依赖 `knowledge.api.RetrievedChunk`，零新依赖；SecurityConfig 零改动。
- 不引运行时新依赖（Testcontainers 为 test scope，K4 已立基建）。
- 引用卡片交互、计量、Rerank、反查、命中测试弹窗改动一律不碰（见「明确不做」）。

## 10. 文档更新（拍板入档）
- `data-model.md`：message 表字段清单补 `sources jsonb`（引用来源快照，随消息落库）。
- 无其它架构文档口径变更（存储/SSE/降级均沿用既有约定）。
