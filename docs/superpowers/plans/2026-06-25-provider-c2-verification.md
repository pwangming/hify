# provider C2 验证文档（getChatClient + 四层韧性 + admin 测试端点）

> 配套：spec `docs/superpowers/specs/2026-06-25-provider-c2-design.md`、plan `docs/superpowers/plans/2026-06-25-provider-c2.md`。
> 用途：C2 实现完成后，按本文逐项验证「自动化测试 + 真实调用管道 + 韧性行为」三层都通过。
> 判定原则：**不 grep `BUILD SUCCESS`**（`-q` 会静音），一律看测试计数 / `echo $?` 退出码。

## 0. 前置准备

- [ ] 本地 PostgreSQL（pgvector）运行，`.env` 主密钥（`hify.provider.crypto.master-key` 来源）已配，与既有库一致。
- [ ] 一个**真实可用**的 openai 兼容供应商凭证（推荐通义千问 DashScope 兼容端点，或任一 OpenAI 兼容网关）。仅人工 e2e 用；自动化测试不需要真实 Key。
- [ ] admin 账号可登录（本地库已 seed，直接登录）。

## 1. 第一层 · 自动化测试（无需真实 LLM）

- [ ] **全量回归**：
  ```bash
  cd server && ./mvnw -q test; echo "exit=$?"
  ```
  期望：`exit=0`；测试总数 ≥ 196（C1 基线）+ 本轮新增（约 +25）。逐 Task 的新增测试类：
  - `ProviderErrorTest`（3 码）
  - `ResilienceExceptionsTest`（分类 7 例）
  - `ResilienceBundleTest`（字段映射）
  - `ResilientChatModelTest`（正常/503 重试/400 不重试/超时）
  - `ResilienceRegistryTest`（缓存命中/4 类不可用/两种失效）
  - `ChatClientFactoryTest`（openai/anthropic/未知协议）
  - `ProviderFacadeImplTest`（getChatClient 委托，增量）
  - `ProviderServiceTest` / `AiModelServiceTest`（失效钩子，增量）
  - `AdminModelControllerTest`（测试端点 ok/403/503，增量）

- [ ] **模块边界**：
  ```bash
  cd server && ./mvnw -q -Dtest=ModularityTests test; echo "exit=$?"
  ```
  期望：`exit=0`。重点：`provider/api` 新增 `ChatClient` 返回类型、`api/dto/ModelTestResponse` 未触发 DTO→entity 越界或 Spring AI 类型越界（provider api 例外已声明）。

- [ ] **关键行为已被测试钉死**（无需真实 LLM 即可确认逻辑正确）：
  - 503 → 重试到 `retry_max_attempts` 次后映射 12003（`ResilientChatModelTest.服务端503_重试到上限后映射503`）。
  - 400 → **不重试**、**不计熔断**（`ResilienceExceptionsTest` + `ResilientChatModelTest.客户端400_不重试_映射503`）。
  - 读超时 → 映射 12003（`ResilientChatModelTest.超时_映射503`）。
  - 信号量满 → 12004 / 熔断打开 → 12003（`ResilienceExceptionsTest`）。
  - 同 modelId 二次取 → 命中缓存、工厂只建一次（`ResilienceRegistryTest.同modelId两次_命中缓存_工厂只建一次`）。

## 2. 第二层 · 真实调用管道（人工，需真实 Key）

> 用 admin「测试连通」端点完成 C2 的真实 e2e 冒烟——这是 C2 没有 conversation 调用方时验证「钥匙解密对、baseUrl 拼对、真能拿到回复」的唯一真实路径。

- [ ] 起后端：`cd server && ./mvnw spring-boot:run`（确认启动日志里 **Flyway 应用了 V8**：`Migrating schema ... to version 8`）。
- [ ] admin 登录取 JWT（沿用既有登录流程 / Postman 登录用例）。
- [ ] admin 新建一个 **enabled** 的 openai 兼容供应商（填真实 baseUrl + Key），其下建一个 **enabled chat** 模型（`modelKey` 用供应商真实模型名，如 `qwen-plus`）。记下该模型 id。
- [ ] **成功路径**：
  ```
  POST /api/v1/admin/provider/models/{id}/test   （Header: Authorization: Bearer <admin jwt>）
  ```
  期望：`200`，`data.sample` 是模型对 "ping" 的真实回复（非空字符串）。
  ✅ 证明：解密 Key 正确、baseUrl/协议拼装正确、四层韧性放行正常调用。

## 3. 第三层 · 韧性与热生效（人工）

- [ ] **热生效（invalidate）**：把该供应商的 Key 改成错误值（admin 编辑供应商，提交）→ **不重启** 立即再 `POST .../test`。
  期望：`503` + `code=12003`（"模型供应商暂时不可用"）。
  ✅ 证明：`ProviderService.update` 调了 `invalidate(providerId)`，旧 ChatClient 缓存被清、按新（错误）Key 重建。

- [ ] **供应商停用 → 12002**：admin 禁用该供应商 → `POST .../test`。
  期望：`400` + `code=12002`（"所选模型不存在或不可用"）。

- [ ] **不存在的 modelId → 12002**：`POST /api/v1/admin/provider/models/999999/test`。
  期望：`400` + `code=12002`。

- [ ] **权限**：用 member token 调 `POST .../test`。
  期望：`403` + `code=10004`。

- [ ] **（可选）熔断打开**：把 Key 改错后连续快速调用 `/test` ≥ 20 次（填满熔断窗口），观察后续若干次响应**毫秒级**返回 12003（而非每次等满超时）。
  ✅ 证明：CircuitBreaker 按 `providerId` 累积失败率并打开。

## 4. Postman 集合

- [ ] `docs/postman/hify-provider-c2.postman_collection.json` 跑通：admin 登录 → 测试连通成功（断言 `data.sample` 存在）→ 不存在 modelId（12002）→ 改错 Key（12003）。结构参考既有 `hify-provider-c1.postman_collection.json`。

## 5. 回归确认（不破坏既有）

- [ ] C1 的成员侧列模型、app 带 modelId 校验（16002）行为不变（`ModelQueryControllerTest` / app 相关测试仍绿）。
- [ ] 既有 provider CRUD（增删改查/启停）行为不变（`ProviderServiceTest` / `AiModelServiceTest` / 两个 Admin*ControllerTest 全绿）。

## 6. 验收判定

全部勾选即 C2 通过。任一未过：
- 自动化层失败 → 回到对应 Task，用 `superpowers:systematic-debugging` 定位，**不要**改测试迁就实现。
- 真实调用层失败但自动化全绿 → 大概率是 Spring AI 1.0.1 的 builder/exception 包装与计划假设有出入（见 plan §Self-Review「已知风险」），按真实异常类型微调 `ChatClientFactory` 构造或 `ResilienceExceptions` 的 cause 链识别，并补一条单测固化。

## 7. 自检归档

- [ ] 每完成一项，把结论（命令 + 退出码/计数 + 现象）追加到 `docs/self-check.md`，标注 C2 + 日期。
