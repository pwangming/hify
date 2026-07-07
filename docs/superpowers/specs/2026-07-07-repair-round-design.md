# 修缮轮设计：BaseURL 通用化 + D2 孤儿会话收尾 + ArchUnit 规则归位

> 2026-07-07 brainstorm 拍板。三项欠账清偿，相互独立、无新功能。
> 背景账目：`llm-resilience.md` §6.1（URL 双拼已知局限）、记忆 D2（同步聊天超时孤儿会话）、K4 执行偏差 1/3（ArchUnit 规则误伤测试类）。

## 决策清单

1. **BaseURL 采用方案甲**：`ChatClientFactory` 显式设 `completionsPath("/chat/completions")` / `embeddingsPath("/embeddings")`，baseUrl 约定翻转为「照抄厂商文档的完整基址（含版本段）」。否决自动探测（魔法行为排障噩梦）与厂商预设模板（即二次开发，Dify 式厂商市场是明确不做项）。
2. **归一化只去尾部斜杠**：工厂内私有静态 `normalizeBaseUrl`，构建时归一（兼容存量任意填法）。不做「误贴 `/chat/completions` 自动剥离」防呆——填错由试连接暴露真实错误（cause 链已随 852d37e 落库+日志）。
3. **V19 迁移补版本段**：`update model_provider set base_url = rtrim(base_url,'/') || '/v1' where protocol='openai' and rtrim(base_url,'/') not like '%/v1'`。存量 deepseek / 千问 compatible-mode 两家补 `/v1` 均正确；`not like` 守卫防重复执行语义错误。
4. **anthropic 协议不动**：其生态惯例即「不带版本段基址 + SDK 拼 `/v1/messages`」。两协议各随其生态惯例，文档写明差异。
5. **D2 两个口子都修**：①同步端点 `send` LLM 失败时调既有 `store.cleanupFailedTurn` 后原样抛错，与流式语义对齐（推翻旧断言「user 消息已落但不落 assistant」，属 bug 修复非契约变更——响应结构与错误码不变）；②SSE 新增开场 `meta` 事件（payload 只含 `conversationId`，Long→string），在首个 delta 前发出；前端 `useChatStream` 处理 meta，新会话立即记 currentId，断网重发进同一会话。SSE 事件「只增不改」，旧客户端忽略未知事件，向后兼容。
6. **ArchUnit 规则只管生产代码**：`LayerRulesTest` 的 `@AnalyzeClasses` 加 `importOptions = ImportOption.DoNotIncludeTests.class`；随后收拢两处 K4 绕行——删 `com.hify.support.service.TransactionalPgIntegrationTest`（`@Transactional` 回到 `PgIntegrationTest` 类上）、`KbChunkRetrievalTest` 归位 `com.hify.knowledge.mapper` 包。挪包后必须 `mvn clean test`（K4 偏差 4 教训：target 旧 class 残留致 ArchUnit 误报）。

## 主流厂商填法表（入档 llm-resilience.md §6.1 替换原表）

| 厂商 | baseUrl 填法（照抄文档完整基址） |
|---|---|
| OpenAI / DeepSeek / Moonshot / 腾讯混元 / SiliconFlow / xAI / Ollama·vLLM 本地 | `https://.../v1` |
| 阿里百炼（compatible-mode 网关） | `https://<网关>/compatible-mode/v1` |
| 火山方舟 Ark | `https://ark.cn-beijing.volces.com/api/v3` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` |
| 百度千帆 v2 | `https://qianfan.baidubce.com/v2` |
| Google Gemini（OpenAI 兼容层） | `https://generativelanguage.googleapis.com/v1beta/openai` |

平台在其后只拼 `/chat/completions` 与 `/embeddings`，任何前缀形态的网关均可接入，零厂商代码。

## 改动清单

**provider**：`ChatClientFactory`（显式路径 + normalizeBaseUrl）、`V19__provider_base_url_add_version_segment.sql`、`llm-resilience.md` §6.1 重写、`ProviderList.vue` baseUrl 输入框下加提示行「照抄厂商文档的完整基址（含版本段），如 https://api.deepseek.com/v1」。

**conversation**：`ConversationService.send`（失败清理）、`StreamEvent` 增 `Meta`、`ConversationController.toSse` 映射 `event: meta`、`StreamPayloads` 增 Meta 载荷、`web useChatStream.ts` 增 onMeta 处理、聊天 store/视图记 currentId。

**测试侧**：`LayerRulesTest`、删 `TransactionalPgIntegrationTest`、挪 `KbChunkRetrievalTest`。

## 测试策略

- `normalizeBaseUrl` 纯函数直测（尾斜杠单/多/无、根路径边界）。
- 工厂显式路径装配断言：计划阶段在「反射读 OpenAiApi 字段」与「行为测试」间择一，以最简可行为准。
- `send` 失败清理：mock 单测——LLM 抛错时 verify `cleanupFailedTurn(cid, userMessageId, newConversation)` 且异常原样透传；新旧会话两种入参。
- SSE meta：service 层断言事件序列首个为 `Meta(cid)`；controller 层断言 `event: meta` 与 payload；前端 vitest 断言 onMeta 触发与 currentId 写入、未知事件不炸。
- 迁移 V19：连库测试基建已有（继承 `PgIntegrationTest` 即自动跑全量 Flyway），Schema 断言按需。
- 全量回归：`mvn clean test`（clean 必须，见决策 6）+ `pnpm test && pnpm build`。

## 手动验收

1. 迁移后查库：`select name, base_url from model_provider;` 存量两家已带 `/v1`。
2. deepseek / 千问 chat+embedding 试连接全绿（新拼接路径真调通）。
3. （可选加分）接一家非 `/v1` 前缀厂商（火山 Ark 或智谱）试连接通过——通用化的直接证明。
4. 聊天发消息中途断网（DevTools offline）→ 重发 → 侧边栏不重复建会话，且新会话免手动刷新即出现。
5. 停用模型后同步端点发消息（curl 即可）→ 报错后查库无孤儿会话/孤儿 user 消息。
6. （复审追加）聊天回答中途断网 → 气泡半截正文保留、下方红色高亮「⚠️ 网络异常，请稍后重试」、发送态不卡死；断网触发的任何请求 toast 均为中文。

> 复审追加的流式健壮性修复（现象一漏刷、网络错误中文化、axios 英文 toast、断流兜底、错误红块）详见 plan 执行记录「复审追加」章节。

## 明确不做

厂商预设模板、URL 自动探测重试、误贴完整端点自动剥离、anthropic 协议 URL 改动、同步端点客户端断开检测、SSE 事件结构其他变动。
