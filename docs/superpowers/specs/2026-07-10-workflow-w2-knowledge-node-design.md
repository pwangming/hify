# Workflow W2：知识检索节点（设计）

> 2026-07-10 brainstorm 定稿。在 W1 引擎地基（specs/2026-07-09-workflow-w1-engine-design.md）上
> 新增 `knowledge-retrieval` 节点类型，复用 knowledge 模块既有检索能力，跑通
> 「start → 知识检索 → LLM → end」这条 RAG 工作流。

## 1. 范围与目标

**做**：
- `NodeType` 新增 `KNOWLEDGE_RETRIEVAL("knowledge-retrieval")`
- `KnowledgeRetrievalNodeExecutor`（模块内新 executor，注册进 WorkflowEngine 既有扩展点）
- `GraphValidator` 新增该类型的字段格式校验
- 测试三层 + Postman 集合补 W2 验收请求

**不做**：
- 无新表、无 Flyway 迁移（已核实 `workflow_node_run.node_type` 为纯 text 列，无 check 约束）
- 无前端改动（画布是后续轮）
- 不动发布机制、不加其他节点类型
- 不给节点暴露 topK/相似度阈值参数（沿用 knowledge 全局配置 `hify.knowledge.retrieval.*`，
  KnowledgeFacade 刻意只有两参 retrieve）
- 不改 embedding 用量口径（与 conversation 检索一致：不发 TokenUsedEvent，现状如此）

**依赖合法性**：code-organization.md 白名单中 workflow 允许依赖 knowledge，ArchUnit 无需改动。

## 2. 节点定义

graph jsonb 中的节点形态：

```json
{
  "id": "kb",
  "type": "knowledge-retrieval",
  "data": {
    "datasetIds": [1, 2],
    "query": "{{start.input}}"
  }
}
```

| 字段 | 约束 | 校验时机 |
|---|---|---|
| `datasetIds` | 必填、非空数组、每项可解析为 long | 保存草稿时（GraphValidator，纯格式） |
| `query` | 必填非空字符串，支持 `{{nodeId.field}}` 引用 | 保存时查必填；引用合法性走 W1 既有变量引用校验 |

**存在性校验仅运行时做**（用户拍板）：GraphValidator 保持纯函数不碰库，与 W1
「modelId 只查格式、模型可用性不在保存时校验」先例一致。保存时校验存在性给不了运行时保证
（库随时可被删），两头校验收益不大；而运行时不校验的话，库被删后 retrieve 会静默返回空命中，
问题被吞掉——所以运行时必须先 validate 再 retrieve。

**输出**（用户拍板：text + count）：

```json
{ "text": "[1] 段落一\n[2] 段落二", "count": 2 }
```

- `text`：命中段拼接文本，格式沿用 conversation 的 `[序号] 内容` 约定，按相似度降序；
  下游提示词直接写 `{{kb.text}}`
- `count`：命中条数；为日后条件分支节点判断「是否查到」预留抓手
- 无命中时 `{ "text": "", "count": 0 }`，节点 **succeeded**——无命中是业务结果不是错误

**拼接逻辑放 executor 内部**（用户拍板）：conversation 的拼接需求不同
（要拼系统提示词头部 + 生成 sources 快照），rule-of-three 未到，不抽象、不下沉 facade。

## 3. 执行流程与错误处理

`KnowledgeRetrievalNodeExecutor implements NodeExecutor`，执行步骤：

1. **渲染 query**：`ctx.render(...)`；引用缺失抛 IllegalStateException → 引擎按节点失败处理（W1 既有机制）
2. **存在性校验**：`knowledgeFacade.validateDatasetIds(datasetIds)`；库被删抛 BizException(10005)
3. **检索**：`knowledgeFacade.retrieve(datasetIds, renderedQuery)`
4. **拼接输出**：`[i] content` 按行拼 text，count = 命中数

**inputs 快照**：`{ datasetIds, query(渲染后) }`。步骤 2/3 的异常包进
`NodeExecutionException(inputs, e)` 抛出，渲染后的输入落 `node_run.inputs` 供排障
（复用 W1 LLM 节点模式）。

**失败语义（用户拍板：节点失败，不降级）**：embedding 模型未配（12006）、供应商超时/熔断
（12003/12004）、库被删（10005）均导致节点 failed → run failed。与 conversation 的降级
刻意不同：聊天中断体验差所以降级；工作流是自动化任务，「结果错了」比「没结果」更糟——
降级会让下游 LLM 拿着空资料一本正经地编。与 W1「LLM 节点失败即 run failed」语义一致。

节点重试固定 0（同 LLM 节点，llm-resilience：防节点×provider 重试风暴；retrieve 内部
embedding 调用已有 provider 层韧性）。

## 4. 测试与 DoD

**单测**（executor，mock KnowledgeFacade）：
- 正常命中：拼接格式正确、count 正确、inputs 含渲染后 query
- 无命中：`text=""`、`count=0`、节点成功
- facade 抛 BizException：转 NodeExecutionException 且携带 inputs

**GraphValidator 单测**：datasetIds 缺失/空数组/非数字、query 缺失的反例 + 合法正例。

**集成测试**（Testcontainers，复用 W1 黄金链路基建）：
「start → kb → llm(桩) → end」全链路，断言 kb 输出注入下游提示词、
`workflow_node_run` 落库两个业务节点记录。

**DoD 手动验收**（反做假，Postman 集合追加 W2 请求）：
1. 黄金链路：建 workflow 应用 → PUT 含 knowledge-retrieval 节点的草稿（绑真实知识库）→
   POST 触发 → succeeded，outputs 里的回答引用了知识库内容
2. 失败路径：datasetIds 指向已删除的知识库 → run failed（HTTP 200），
   node_run 记录含 10005 错误信息

**既有防线**：ModularityTests / ArchUnit / 全量测试绿。

## 5. 交付物

- workflow 模块：NodeType 枚举 + KnowledgeRetrievalNodeExecutor + GraphValidator 校验
- 测试三层（单测/validator/集成）
- docs/postman 集合追加 W2 请求
- docs/self-check.md 追加本轮自检

**后续轮次展望不变**（W1 spec §6）：条件分支/HTTP 节点 → Vue Flow 画布
（前置：GraphNode/GraphEdge 补 position/sourceHandle）→ 发布机制 + 对外 API →
代码执行节点 → Agent 节点。
