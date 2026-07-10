# Workflow W2 知识检索节点 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `knowledge-retrieval` 节点类型，工作流可查知识库并把命中资料喂给下游 LLM，跑通 start → kb → llm → end 的 RAG 工作流。

**Architecture:** 纯后端、零迁移。复用 W1 的 `NodeExecutor` 扩展点：`NodeType` 加枚举值、`GraphValidator` 加字段格式校验、新建 `KnowledgeRetrievalNodeExecutor` 调 `KnowledgeFacade`（workflow→knowledge 在依赖白名单内）。检索调用失败＝节点失败（不降级）；无命中＝正常空输出。

**Tech Stack:** Spring Boot 3.x / JUnit5 + Mockito / Testcontainers（PgIntegrationTest 基建已有）。

**Spec:** `docs/superpowers/specs/2026-07-10-workflow-w2-knowledge-node-design.md`

## Global Constraints

- 与文档冲突的代码视为错误：api-standards / database-standards / code-organization 优先
- 测试命令在 `server/` 下跑；**判定结果看退出码，不许 grep "BUILD SUCCESS"**（`-q` 会静音）
- node_run.inputs 里的 id 值一律存字符串（对齐 LLM 节点 modelId 先例与「Long 序列化为字符串」规范）
- 节点重试固定 0；executor 抛 `NodeExecutionException(inputs, cause)` 让渲染后输入落库
- 不改 KnowledgeFacade、不发 TokenUsedEvent（embedding 用量口径与 conversation 一致，本轮不动）
- 提交信息用中文、conventional commits（参照 git log 既有风格）

---

### Task 1: NodeType 枚举 + GraphValidator 格式校验

**Files:**
- Modify: `server/src/main/java/com/hify/workflow/constant/NodeType.java`
- Modify: `server/src/main/java/com/hify/workflow/service/engine/GraphValidator.java`（节点类型分支处，约 62-65 行附近）
- Test: `server/src/test/java/com/hify/workflow/service/engine/GraphValidatorTest.java`（追加用例）

**Interfaces:**
- Consumes: 既有 `GraphValidator.invalid(String)`、`NodeType.supported(String)`
- Produces: `NodeType.KNOWLEDGE_RETRIEVAL`（value = `"knowledge-retrieval"`）；GraphValidator 对该类型校验 `datasetIds`（非空数组、每项可 parseLong）与 `query`（必填非空）。Task 2/3 依赖此枚举值。

- [x] **Step 1: 写失败测试**

在 `GraphValidatorTest.java` 追加（沿用该文件既有的 graph 构造辅助方式；若无辅助方法则按下面完整构造）：

```java
// ==== W2: knowledge-retrieval 节点校验 ====

private GraphDef kbGraph(Map<String, Object> kbData) {
    return new GraphDef(List.of(
            new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "q", "required", true)))),
            new GraphNode("kb", "knowledge-retrieval", kbData),
            new GraphNode("end", "end", Map.of("outputs", List.of(Map.of("name", "r", "value", "{{kb.text}}"))))),
            List.of(new GraphEdge("start", "kb"), new GraphEdge("kb", "end")));
}

@Test
void kb节点_合法配置_通过() {
    List<GraphNode> ordered = validator.validateAndOrder(
            kbGraph(Map.of("datasetIds", List.of(1, 2), "query", "{{start.q}}")));
    assertEquals("kb", ordered.get(1).id());
}

@Test
void kb节点_缺datasetIds_拒绝() {
    BizException ex = assertThrows(BizException.class,
            () -> validator.validateAndOrder(kbGraph(Map.of("query", "{{start.q}}"))));
    assertTrue(ex.getMessage().contains("datasetIds"));
}

@Test
void kb节点_datasetIds空数组_拒绝() {
    BizException ex = assertThrows(BizException.class,
            () -> validator.validateAndOrder(kbGraph(Map.of("datasetIds", List.of(), "query", "x"))));
    assertTrue(ex.getMessage().contains("datasetIds"));
}

@Test
void kb节点_datasetIds含非数字_拒绝() {
    BizException ex = assertThrows(BizException.class,
            () -> validator.validateAndOrder(kbGraph(Map.of("datasetIds", List.of("abc"), "query", "x"))));
    assertTrue(ex.getMessage().contains("datasetIds"));
}

@Test
void kb节点_缺query_拒绝() {
    BizException ex = assertThrows(BizException.class,
            () -> validator.validateAndOrder(kbGraph(Map.of("datasetIds", List.of(1)))));
    assertTrue(ex.getMessage().contains("query"));
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
cd server && mvn -Dtest=GraphValidatorTest test
```
Expected: 失败，`kb节点_合法配置_通过` 报「未知节点类型：knowledge-retrieval」（其余 4 条可能因同一异常误绿——message 恰含 datasetIds/query 字样时注意甄别，以合法用例转绿为准）。退出码非 0。

- [x] **Step 3: 实现**

`NodeType.java` 枚举加一行（LLM 与 END 之间）：

```java
    LLM("llm"),
    KNOWLEDGE_RETRIEVAL("knowledge-retrieval"),
    END("end");
```

`GraphValidator.validateAndOrder` 的类型分支（`requireLlmField` 两行之后）追加：

```java
            if (NodeType.KNOWLEDGE_RETRIEVAL.value().equals(n.type())) {
                requireKnowledgeRetrievalFields(n);
            }
```

私有方法（放 `requireLlmField` 之后）：

```java
    /** knowledge-retrieval 节点只做格式校验；datasetIds 存在性留到运行时（spec §2：库随时可被删，保存时校验给不了保证）。 */
    private void requireKnowledgeRetrievalFields(GraphNode n) {
        Object query = n.data() == null ? null : n.data().get("query");
        if (query == null || String.valueOf(query).isBlank()) {
            throw invalid("knowledge-retrieval 节点 " + n.id() + " 缺少 query");
        }
        Object raw = n.data().get("datasetIds");
        if (!(raw instanceof Collection<?> ids) || ids.isEmpty()) {
            throw invalid("knowledge-retrieval 节点 " + n.id() + " 缺少非空数组 datasetIds");
        }
        for (Object v : ids) {
            try {
                Long.parseLong(String.valueOf(v));
            } catch (NumberFormatException e) {
                throw invalid("knowledge-retrieval 节点 " + n.id() + " 的 datasetIds 含非法值：" + v);
            }
        }
    }
```

（`java.util.Collection` 已在该文件 import 列表中。）

- [x] **Step 4: 跑测试确认通过**

```bash
cd server && mvn -Dtest=GraphValidatorTest test
```
Expected: 全绿，退出码 0。

- [x] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/workflow/constant/NodeType.java \
        server/src/main/java/com/hify/workflow/service/engine/GraphValidator.java \
        server/src/test/java/com/hify/workflow/service/engine/GraphValidatorTest.java
git commit -m "feat(workflow): knowledge-retrieval 节点类型与图校验（datasetIds/query 仅格式校验）"
```

---

### Task 2: KnowledgeRetrievalNodeExecutor

**Files:**
- Create: `server/src/main/java/com/hify/workflow/service/engine/KnowledgeRetrievalNodeExecutor.java`
- Test: `server/src/test/java/com/hify/workflow/service/engine/KnowledgeRetrievalNodeExecutorTest.java`

**Interfaces:**
- Consumes: `NodeType.KNOWLEDGE_RETRIEVAL`（Task 1）；`KnowledgeFacade.validateDatasetIds(List<Long>)` / `retrieve(List<Long>, String)` → `List<RetrievedChunk>`（record: `chunkId, documentId, documentName, content, score`）；`RunContext.render(String)`；`NodeExecutor` / `NodeResult` / `NodeExecutionException(Map, Throwable)`
- Produces: Spring `@Component`，`type()="knowledge-retrieval"`，outputs = `{text: String, count: Integer}`，inputs = `{datasetIds: List<String>, query: 渲染后String}`。引擎按 type 自动发现（W1 既有机制），无需改 WorkflowEngine。

- [x] **Step 1: 写失败测试**

新建 `KnowledgeRetrievalNodeExecutorTest.java`：

```java
package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.knowledge.api.KnowledgeFacade;
import com.hify.knowledge.api.RetrievedChunk;
import com.hify.workflow.dto.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalNodeExecutorTest {

    private KnowledgeFacade knowledgeFacade;
    private KnowledgeRetrievalNodeExecutor executor;
    private RunContext ctx;

    @BeforeEach
    void setUp() {
        knowledgeFacade = mock(KnowledgeFacade.class);
        executor = new KnowledgeRetrievalNodeExecutor(knowledgeFacade);
        ctx = new RunContext(7L, 42L);
        ctx.putOutput("start", Map.of("q", "退货政策是什么"));
    }

    private GraphNode node() {
        return new GraphNode("kb", "knowledge-retrieval",
                Map.of("datasetIds", List.of(1, 2), "query", "{{start.q}}"));
    }

    @Test
    void 命中两段_拼接text_count正确_inputs含渲染后query() {
        when(knowledgeFacade.retrieve(List.of(1L, 2L), "退货政策是什么")).thenReturn(List.of(
                new RetrievedChunk(11L, 1L, "客服手册", "七天无理由退货", 0.92),
                new RetrievedChunk(12L, 1L, "客服手册", "运费由卖家承担", 0.85)));

        NodeResult result = executor.execute(node(), ctx);

        assertEquals("[1] 七天无理由退货\n[2] 运费由卖家承担", result.outputs().get("text"));
        assertEquals(2, result.outputs().get("count"));
        assertEquals("退货政策是什么", result.inputs().get("query"));
        assertEquals(List.of("1", "2"), result.inputs().get("datasetIds"));
        verify(knowledgeFacade).validateDatasetIds(List.of(1L, 2L));
    }

    @Test
    void 无命中_空text_count为0_节点成功() {
        when(knowledgeFacade.retrieve(anyList(), anyString())).thenReturn(List.of());

        NodeResult result = executor.execute(node(), ctx);

        assertEquals("", result.outputs().get("text"));
        assertEquals(0, result.outputs().get("count"));
    }

    @Test
    void 库被删_validate抛Biz_转NodeExecutionException携带渲染后inputs() {
        org.mockito.Mockito.doThrow(new BizException(CommonError.NOT_FOUND, "知识库不存在"))
                .when(knowledgeFacade).validateDatasetIds(anyList());

        NodeExecutionException ex = assertThrows(NodeExecutionException.class,
                () -> executor.execute(node(), ctx));

        assertEquals(BizException.class, ex.getCause().getClass());
        assertEquals("退货政策是什么", ex.inputs().get("query"));
    }
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
cd server && mvn -Dtest=KnowledgeRetrievalNodeExecutorTest test
```
Expected: 编译失败「找不到符号 KnowledgeRetrievalNodeExecutor」。退出码非 0。

- [x] **Step 3: 实现**

新建 `KnowledgeRetrievalNodeExecutor.java`：

```java
package com.hify.workflow.service.engine;

import com.hify.knowledge.api.KnowledgeFacade;
import com.hify.knowledge.api.RetrievedChunk;
import com.hify.workflow.constant.NodeType;
import com.hify.workflow.dto.GraphNode;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索节点：渲染 query → 校验库存在（10005）→ KnowledgeFacade.retrieve → 输出 {text, count}。
 * 检索失败＝节点失败（不降级，spec §3：工作流要可排查，降级会让下游拿空资料一本正经地编）；
 * 无命中是业务结果不是错误（text=""/count=0，节点成功）。topK/阈值走 knowledge 全局配置，节点不暴露。
 */
@Component
public class KnowledgeRetrievalNodeExecutor implements NodeExecutor {

    private final KnowledgeFacade knowledgeFacade;

    public KnowledgeRetrievalNodeExecutor(KnowledgeFacade knowledgeFacade) {
        this.knowledgeFacade = knowledgeFacade;
    }

    @Override
    public String type() {
        return NodeType.KNOWLEDGE_RETRIEVAL.value();
    }

    @Override
    public NodeResult execute(GraphNode node, RunContext ctx) {
        List<Long> datasetIds = ((Collection<?>) node.data().get("datasetIds")).stream()
                .map(v -> Long.parseLong(String.valueOf(v))).toList();   // validator 已保证合法
        String query = ctx.render(String.valueOf(node.data().get("query")));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("datasetIds", datasetIds.stream().map(String::valueOf).toList());
        inputs.put("query", query);

        try {
            knowledgeFacade.validateDatasetIds(datasetIds);
            List<RetrievedChunk> chunks = knowledgeFacade.retrieve(datasetIds, query);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < chunks.size(); i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append('[').append(i + 1).append("] ").append(chunks.get(i).content());
            }
            return new NodeResult(inputs, Map.of("text", sb.toString(), "count", chunks.size()));
        } catch (Exception e) {
            // 渲染已成功、检索才失败：渲染后的输入随异常带出，落 node_run.inputs 供排障（同 LLM 节点模式）
            throw new NodeExecutionException(inputs, e);
        }
    }
}
```

- [x] **Step 4: 跑测试确认通过**

```bash
cd server && mvn -Dtest=KnowledgeRetrievalNodeExecutorTest test
```
Expected: 3 条全绿，退出码 0。

- [x] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/workflow/service/engine/KnowledgeRetrievalNodeExecutor.java \
        server/src/test/java/com/hify/workflow/service/engine/KnowledgeRetrievalNodeExecutorTest.java
git commit -m "feat(workflow): 知识检索节点 executor（失败不降级，输出 text+count）"
```

---

### Task 3: 集成测试（RAG 黄金链路 + 已删库失败路径）

**Files:**
- Modify: `server/src/test/java/com/hify/workflow/WorkflowRunFlowTest.java`（追加 @MockitoBean 与两个测试方法）

**Interfaces:**
- Consumes: Task 1/2 全部产物；既有 `PgIntegrationTest` 基建、`draftService.saveDraft` / `runService.run`
- Produces: 无新接口，纯验证。

- [x] **Step 1: 写失败测试**

`WorkflowRunFlowTest` 类内追加 mock（放 `llmCaller` 声明之后）：

```java
    @MockitoBean
    private com.hify.knowledge.api.KnowledgeFacade knowledgeFacade;
```

追加图构造与两个测试方法（放 `运行历史_游标翻页穿真库` 之后）：

```java
    /** W2：start → kb → llm → end 的 RAG 链路图。 */
    private GraphDef ragGraph() {
        return new GraphDef(List.of(
                new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "query", "required", true)))),
                new GraphNode("kb", "knowledge-retrieval", Map.of("datasetIds", List.of(1), "query", "{{start.query}}")),
                new GraphNode("llm_1", "llm", Map.of("modelId", "3",
                        "userPrompt", "参考资料：{{kb.text}}\n请回答：{{start.query}}")),
                new GraphNode("end", "end", Map.of("outputs", List.of(Map.of("name", "answer", "value", "{{llm_1.text}}"))))),
                List.of(new GraphEdge("start", "kb"), new GraphEdge("kb", "llm_1"), new GraphEdge("llm_1", "end")));
    }

    @Test
    void RAG链路_检索结果注入下游提示词_四节点日志齐全() {
        when(knowledgeFacade.retrieve(eq(List.of(1L)), eq("怎么退货")))
                .thenReturn(List.of(new com.hify.knowledge.api.RetrievedChunk(
                        11L, 1L, "客服手册", "七天无理由退货", 0.9)));
        when(llmCaller.call(any(), eq(null), eq("参考资料：[1] 七天无理由退货\n请回答：怎么退货")))
                .thenReturn(new LlmCallResult("支持七天无理由退货", 20, 8));

        draftService.saveDraft(appId, ragGraph(), owner);
        RunResponse resp = runService.run(appId, Map.of("query", "怎么退货"), owner);

        assertEquals("succeeded", resp.status());
        assertEquals("支持七天无理由退货", resp.outputs().get("answer"));
        assertEquals(List.of("start", "kb", "llm_1", "end"),
                resp.nodeRuns().stream().map(n -> n.nodeId()).toList());
        // kb 节点 inputs/outputs 落库如实
        assertEquals("怎么退货", resp.nodeRuns().get(1).inputs().get("query"));
        assertEquals(1, resp.nodeRuns().get(1).outputs().get("count"));
        Integer nodeRows = jdbc.queryForObject(
                "select count(*) from workflow_node_run where run_id = ?", Integer.class, resp.id());
        assertEquals(4, nodeRows);
    }

    @Test
    void 知识库被删_kb节点失败_run置failed_下游不执行() {
        org.mockito.Mockito.doThrow(new com.hify.common.exception.BizException(
                        com.hify.common.exception.CommonError.NOT_FOUND, "知识库不存在或已删除"))
                .when(knowledgeFacade).validateDatasetIds(any());

        draftService.saveDraft(appId, ragGraph(), owner);
        RunResponse resp = runService.run(appId, Map.of("query", "怎么退货"), owner);   // 不抛异常

        assertEquals("failed", resp.status());
        assertTrue(resp.errorMessage().contains("kb"));
        assertEquals(2, resp.nodeRuns().size());   // start + kb，llm/end 未开工
        assertEquals("failed", resp.nodeRuns().get(1).status());
        assertEquals("怎么退货", resp.nodeRuns().get(1).inputs().get("query"));   // 渲染后输入落库供排障
    }
```

- [x] **Step 2: 跑集成测试**

```bash
cd server && mvn -Dtest=WorkflowRunFlowTest test
```
Expected: 若 Task 1/2 正确，两条新用例直接绿（此 task 是端到端验证网，不是红绿循环）；W1 既有 3 条不回归。退出码 0。若 `count` 断言失败提示 Integer/Long 不匹配——jsonb 回读数字类型以实际为准修断言（改为 `assertEquals(1, ((Number) ...).intValue())`），不改生产代码。

- [x] **Step 3: 全量回归（含模块边界）**

```bash
cd server && mvn verify
```
Expected: 退出码 0（ModularityTests / ArchUnit 含在内；workflow→knowledge 在白名单，不应报违规）。

- [x] **Step 4: Commit**

```bash
git add server/src/test/java/com/hify/workflow/WorkflowRunFlowTest.java
git commit -m "test(workflow): W2 RAG 黄金链路与已删库失败路径集成测试"
```

---

### Task 4: Postman 验收集合 + 自检入档

**Files:**
- Create: `docs/postman/workflow-w2.postman_collection.json`
- Modify: `docs/self-check.md`（追加 W2 一节）

**Interfaces:**
- Consumes: 运行中的本地服务 + 既有真实知识库（K 轮已建、含已向量化文档）
- Produces: 手动验收材料（DoD Step，用户实测）。

- [x] **Step 1: 写 Postman 集合**

以 `docs/postman/workflow-w1.postman_collection.json` 为骨架复制（保留登录/建 app 请求与 token/appId 捕获脚本、variable 约定），改名 `Hify · Workflow W2 知识检索节点`，collection 变量在 W1 基础上增加 `datasetId`（留空，验收时手填真实库 id）。请求列表改为：

1. `0. 登录（拿 token）`——同 W1
2. `1. 查可用 chat 模型`——同 W1（捕获 modelId）
3. `2. 创建 workflow 应用`——同 W1（捕获 appId）
4. `3. 保存 RAG 草稿（start → kb → llm → end）`——PUT `{{baseUrl}}/api/v1/workflow/apps/{{appId}}/draft`，body：

```json
{
  "graph": {
    "nodes": [
      {"id": "start", "type": "start", "data": {"inputs": [{"name": "query", "required": true}]}},
      {"id": "kb", "type": "knowledge-retrieval", "data": {"datasetIds": [{{datasetId}}], "query": "{{start.query}}"}},
      {"id": "llm_1", "type": "llm", "data": {"modelId": "{{modelId}}", "systemPrompt": "只根据参考资料回答，资料没有就说不知道", "userPrompt": "参考资料：{{kb.text}}\n\n问题：{{start.query}}"}},
      {"id": "end", "type": "end", "data": {"outputs": [{"name": "answer", "value": "{{llm_1.text}}"}]}}
    ],
    "edges": [
      {"source": "start", "target": "kb"},
      {"source": "kb", "target": "llm_1"},
      {"source": "llm_1", "target": "end"}
    ]
  }
}
```

> 注意：Postman 的 `{{datasetId}}`/`{{modelId}}` 变量语法与工作流 `{{start.query}}` 变量语法撞车——Postman 只会替换**它认识的 collection 变量**，`{{start.query}}`、`{{kb.text}}`、`{{llm_1.text}}` 不在变量表里会原样透传，正好是我们要的行为。集合描述里写明这一点，防验收人误改。

5. `4. 触发运行（问知识库里有的问题）`——POST `{{baseUrl}}/api/v1/workflow/apps/{{appId}}/runs`，body `{"inputs": {"query": "<验收时改成知识库里真实内容的问题>"}}`；验收断言：HTTP 200、`status=succeeded`、`outputs.answer` 引用了知识库内容、nodeRuns 里 kb 节点 `outputs.count ≥ 1`
6. `5.【失败路径准备】存 datasetIds 指向不存在库的草稿`——同请求 3 但 `"datasetIds": [999999]`；预期 **200 成功**（保存只做格式校验，spec §2）
7. `6.【失败路径】触发 → HTTP 200 但 run=failed`——同请求 5 的触发；预期 `status=failed`、`errorMessage` 含 kb 节点、nodeRuns 中 kb 节点 failed 且 inputs 里能看到渲染后的 query（排障可用性）
8. `7.【收尾】把好草稿存回去`——同请求 4

- [x] **Step 2: 校验 JSON 合法**

```bash
python3 -m json.tool docs/postman/workflow-w2.postman_collection.json > /dev/null && echo OK
```
Expected: `OK`

- [x] **Step 3: docs/self-check.md 追加 W2 自检**

按该文件既有格式追加一节：本轮范围（NodeType/Validator/Executor/集成测试/Postman）、四个拍板决策（失败不降级、text+count、仅运行时校验存在性、executor 自己拼）、测试结果（三层各多少条、mvn verify 退出码）、DoD 待办（用户 Postman 实测两条路径）。

- [x] **Step 4: Commit**

```bash
git add docs/postman/workflow-w2.postman_collection.json docs/self-check.md
git commit -m "docs(postman): W2 验收集合（RAG 黄金链路 + 已删库失败路径）；自检入档"
```

---

## 验收 DoD（用户手动，反做假）

本地起服务（真库真模型），Postman 跑 W2 集合：
1. 黄金链路：绑真实知识库 → 触发 → succeeded 且回答引用知识库内容
2. 失败路径：datasetIds 指向不存在的库 → 保存成功、触发得 HTTP 200 + run failed + node_run 可排障

全部通过后由用户确认，再走 finishing-a-development-branch 收尾。
