# 自检 · ⑤ conversation SSE 流式输出

> 分支 `feat/conversation-sse-streaming`（基线 main 顶 `5cd05e1`），9 个实现/修复提交（`5adcc52`..`76bb823`）。
> 设计见 `docs/superpowers/specs/2026-06-29-conversation-sse-streaming-design.md`，计划见
> `docs/superpowers/plans/2026-06-29-conversation-sse-streaming.md`。

## 一、做了什么

把"发消息"从一次性阻塞 `.call()` 升级为流式 `.stream()`：模型逐 token 吐字、前端打字机增量渲染。

- **provider**：`ResilientChatModel.stream()` 够用子集韧性（Bulkhead + 三层超时「首token30s/间隔60s/总10min」+ 熔断，**不重试**）。
- **conversation**：`ConversationService.sendStream` → `Flux<StreamEvent>`（delta 流 + `concatWith` 尾部 `Mono` 落库；事务B 仅在流正常完成后执行 → **半截不落**；尾部 `subscribeOn(boundedElastic)` 把阻塞 JDBC 移出事件循环）；新增 `POST /api/v1/conversation/messages/stream` 返回 `Flux<ServerSentEvent>`（四事件 message/error/done + 15s 心跳 + 终态取消上游）。
- **前端**：`useChatStream`（fetch + ReadableStream，非 EventSource）；store 流式 `send`（打字机增量 + 错误内联 + 切会话/卸载止血，abort 静默）；删除 125s `chatApiTimeout` workaround。

## 二、自动化验证（已通过）

| 项 | 结果 |
|---|---|
| 后端全量 `mvn test`（含 ArchUnit/Modulith） | **Tests run 262, Failures 0, Errors 0** |
| 前端 `pnpm vitest run` | **145 passed** |
| `pnpm type-check` | clean |
| `pnpm build` | ok |

## 三、代码评审（已通过）

- 逐任务双评审（spec 合规 + 代码质量）全部 Approved；过程中捕获并修复：Task1 回归（ResilienceRegistryTest NPE）、Task3 过宽的全局 Integer→String（已撤销，done token 改回数字与 MessageView 一致）、Task5 `void chat.start` 静默挂起。
- 整支复审（opus）= **可合并（带修复）**；新发现 2 处 Important 已修：① abort 中途切会话误报错 → AbortError 静默；② 事务B 阻塞 JDBC 跑在事件循环 → 移出到 boundedElastic。所有 Minor 判为可跟进、不阻塞。

## 四、待手验：真实模型流式（你来做）

> 配一个真实可用的 chat 模型，进入对话应用逐项验：结果打 ✅/❌ 追加到本节末。

- [ ] **基本流式**：发一句，助手气泡逐字出现（打字机），结束后刷新页面历史仍完整一致。
- [ ] **多轮流式**：连发 2-3 轮，模型能引用上文（滑动窗口 N=10 仍生效）。
- [ ] **usage 落库**（spec §8 风险①）：发完查库 `message` 行 `prompt_tokens/completion_tokens` 是否非 0；若供应商不回 usage 则记 0（OpenAI 需 `include_usage`、Anthropic 走 message_delta）——确认实际行为。
- [ ] **长生成不误杀**（风险②）：让模型输出一大段长文，确认不被三层超时误掐（首token/间隔正常、总时长 10min 内）。
- [ ] **中途切会话止血**（风险③）：吐字中途切到另一会话——确认原流被取消（不再继续烧 token）、**不弹错误**、不污染新会话气泡。
- [ ] **模型报错内联**：故意配一个坏 key/停用模型，确认错误以 `error` 事件内联显示（连接前错误走 JSON 信封）。
- [ ] **nginx 缓冲**（风险④）：经 nginx 部署形态访问，确认 `proxy_buffering off` 生效、打字机不被整段缓冲后才出现。

手验结论：_（待填）_

## 五、刻意延后（非本轮）

- **D2 孤儿会话**：`openTurn` 在调模型前落 user 消息；SSE 已消除"客户端超时 abort"这一主因，真·模型失败的孤儿留后续轮统一处理。
- **usage 配额**：`QuotaGuard.check` 仍空锚点（下一轮）。
- **E2E 基建**、停止/重新生成按钮。
- error 事件 `traceId` 在 Reactor 线程为 null（reactive MDC 传播，后续）。
- 测试覆盖缺口（不影响正确性）：流式总时长/间隔超时层、跨分块单帧解析、`sendStream` 窗口入参 `eq` 断言。
