# 留账清理轮 Task 5

Status: DONE_WITH_ENVIRONMENT_BLOCKER

## Evidence

`cd server && mvn -q verify; echo EXIT=$?`：放权与 `MAVEN_OPTS='-XX:+EnableDynamicAgentLoading'` 均 `EXIT=1`，713 tests / 537 errors，错误统一为 Mockito/Byte Buddy `Could not self-attach to current VM`；无业务断言失败证据。

`cd web && pnpm test`：60 files / 410 tests，`EXIT=0`。

`cd web && pnpm e2e`：`4 passed`，`EXIT=0`（放权 Docker 环境）。

文档模型与 ER 图已移除 daily_usage、登记 V27 观测列并重新生成 SVG。
