# Agent/tool ② — T1：tool 地基 + 2 内置工具 + function-calling 同步闭环 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让被标记为 Agent 的对话应用能在一次同步对话里让模型真实调用 HTTP / 代码两个内置工具、用结果继续作答，工具调用轨迹落 `message.tool_calls`。

**Architecture:** tool 模块从空到有（`tool` 表 + 注册表 + 暴露 Spring AI `ToolCallback` 的 Facade + 2 内置工具执行器）；conversation 新增手动 tool-calling 循环（`internalToolExecutionEnabled(false)`，读回工具调用直接 `ToolCallback.call(argsJson)` 执行，保留 provider 韧性）；app 加 `agentEnabled` 开关分流；累计各轮 token 在落库事务发一次 `TokenUsedEvent`。

**Tech Stack:** Spring Boot 3 + Java 21 虚拟线程 + Spring AI 1.0.1（Tool Calling）+ MyBatis-Plus + PostgreSQL 16 + Flyway + JUnit5/Mockito + Testcontainers。

## Global Constraints（每个 Task 隐含遵守）

- **写代码前重读** `docs/architecture/code-organization.md`、`api-standards.md`、`database-standards.md`、`coding-standards.md`。
- 跨模块只 import 对方 `api` 包；同步调用走 Facade；Long 序列化为字符串（全局 Jackson 已配）。
- **错误码**：优先复用 `CommonError`（10xxx）；conversation 特有码用 **17xxx**（`ConversationError`）；tool 段为 13xxx（本轮基本不需自定义，工具失败以文本回给模型）。
- **事务纪律**：`@Transactional` 只在 service；循环里的模型调用/工具外呼是外部 IO，**全程无事务**；只有落库那步进事务；`TokenUsedEvent` 必须在事务方法内发布（AFTER_COMMIT 才触发）。
- **不新增技术栈依赖**；所有超时/阈值外化到 `application.yml`。
- **DTO 禁止 import entity**（ArchUnit 守）；投影写在 service。
- 判定测试结果**不要 grep "BUILD SUCCESS"**（`-q` 会静音）——看 Tests run 行/退出码（见 memory [[mvn-quiet-verify-pitfall]]）。
- 后端连库测试用 Testcontainers（knowledge 轮已趟平环境）。

## 关键接口契约（各 Task 依赖，签名已从 jar 核实）

- Spring AI 1.0.1：
  - `org.springframework.ai.tool.ToolCallback`：`ToolDefinition getToolDefinition()`；`String call(String toolInput)`；`default String call(String, ToolContext)`。
  - `org.springframework.ai.tool.definition.DefaultToolDefinition.builder().name(String).description(String).inputSchema(String).build()` → `ToolDefinition`。
  - `org.springframework.ai.model.tool.ToolCallingChatOptions.builder().toolCallbacks(List<ToolCallback>).internalToolExecutionEnabled(Boolean).build()`。
  - `ChatClient.prompt().messages(List<Message>).options(ToolCallingChatOptions).call().chatResponse()` → `ChatResponse`。
  - `ChatResponse.hasToolCalls()`→boolean；`.getResult().getOutput()`→`AssistantMessage`；`.getMetadata().getUsage()`→`Usage`。
  - `AssistantMessage.getToolCalls()`→`List<AssistantMessage.ToolCall>`；`ToolCall.id()/name()/arguments()`（arguments 是 JSON 字符串）。
  - `new ToolResponseMessage.ToolResponse(String id, String name, String responseData)`；`new ToolResponseMessage(List<ToolResponse>)`。
  - `new AssistantMessage(String text, Map<String,Object> props, List<ToolCall> toolCalls)`（构造测试用）。
- infra 复用：`OutboundHttpClient.send(String method,String url,Map<String,String> headers,String body)`→`OutboundResponse(int status,String body,Map headers)`（网络/SSRF 失败抛 `BizException`）；`SandboxClient.run(String code,Map<String,String> inputs)`→`SandboxResult(boolean ok,Map<String,Object> outputs,String error)`（沙箱契约：用户码须定义 `main(**inputs)` 返回 dict）。
- provider：`ProviderFacade.getChatClient(Long modelId)`→带韧性 `ChatClient`。

---

## Task 1: `tool` 表 + `Tool` 实体 + `ToolMapper`

**Files:**
- Create: `server/src/main/resources/db/migration/V23__create_tool.sql`
- Create: `server/src/main/java/com/hify/tool/entity/Tool.java`
- Create: `server/src/main/java/com/hify/tool/mapper/ToolMapper.java`
- Test: `server/src/test/java/com/hify/tool/mapper/ToolMapperIT.java`

**Interfaces:**
- Produces: `Tool` 实体（getName/getDescription/getSource/getEnabled/getOwnerId + BaseEntity）；`ToolMapper extends BaseMapper<Tool>`；DB 中 2 行 builtin 种子（`http_request`、`code_executor`）。

- [ ] **Step 1: 写迁移脚本**

`V23__create_tool.sql`（照 V7 app 表 DDL 风格；spec 列 T1 不映射，留 T3；owner_id 内置为空）：

```sql
-- V23：工具统一注册表（tool 模块）。source 分 builtin/openapi/mcp；
-- builtin 的 spec/owner_id 为空，name 即模型寻址标识与内置执行器绑定键。
-- app_tool_rel(T2)、mcp_server(T4) 另轮建表。

create table tool (
    id          bigint      generated always as identity primary key,
    name        text        not null check (char_length(name) <= 64),
    description text        not null check (char_length(description) <= 500),
    source      text        not null check (source in ('builtin', 'openapi', 'mcp')),
    enabled     boolean     not null default true,
    spec        jsonb,
    owner_id    bigint,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table tool is '工具统一注册表（tool 模块）：source 分 builtin/openapi/mcp；builtin 的 spec/owner_id 为空，name 即执行器绑定键';

-- 工具名唯一（部分唯一索引，配合软删可同名重建；模型按 name 寻址、Spring AI 要求名唯一）
create unique index tool_name_uq on tool (name) where deleted = false;

-- 播种 2 个内置工具（description 给模型看，直接影响其是否/如何调用）
insert into tool (name, description, source) values
  ('http_request',
   '发起一次 HTTP 请求并返回状态码与响应体。参数：method（GET/POST/PUT/DELETE/PATCH 之一）、url（完整 http/https 地址）、headers（可选，请求头对象）、body（可选，请求体字符串）。仅用于访问公网可达的 http/https 接口。',
   'builtin'),
  ('code_executor',
   '在隔离沙箱中执行一段 Python 代码并返回结果。你的代码必须定义一个无参函数 main() 并返回一个 dict（结果放进该 dict）。参数：code（完整 Python 源码字符串）。示例：def main():\n    return {"answer": 2 + 2}',
   'builtin');
```

- [ ] **Step 2: 写实体**

`Tool.java`（不映射 spec 列——T1 内置恒 null，T3 再加；owner_id 映射备用）：

```java
package com.hify.tool.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 工具注册表 {@code tool} 映射实体。source 分 builtin/openapi/mcp；
 * name 为模型寻址标识与内置执行器绑定键。spec(jsonb) 本轮不映射（builtin 恒空，留 T3 OpenAPI）。
 */
@TableName("tool")
public class Tool extends BaseEntity {

    private String name;
    private String description;
    private String source;
    private Boolean enabled;
    private Long ownerId;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
}
```

- [ ] **Step 3: 写 Mapper**

```java
package com.hify.tool.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.tool.entity.Tool;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ToolMapper extends BaseMapper<Tool> {
}
```

- [ ] **Step 4: 写 Testcontainers 测试（先失败）**

参照 knowledge 轮既有 Testcontainers 基类（找 `server/src/test/java` 下带 `@Testcontainers`/`@SpringBootTest` 且启 postgres 的样板，复用其容器与 Flyway 迁移，勿新造容器）。

```java
package com.hify.tool.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.tool.entity.Tool;
import org.junit.jupiter.api.Test;
// ... 复用既有 Testcontainers + Spring Boot 测试基类的注解与容器（照 knowledge 轮 IT）

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolMapperIT /* extends 既有 Testcontainers 基类 */ {

    // @Autowired ToolMapper toolMapper;

    @Test
    void 播种的两个内置工具可读且字段正确() {
        List<Tool> builtins = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .eq(Tool::getSource, "builtin").orderByAsc(Tool::getName));
        assertThat(builtins).extracting(Tool::getName)
                .containsExactly("code_executor", "http_request");
        assertThat(builtins).allMatch(t -> Boolean.TRUE.equals(t.getEnabled()));
        assertThat(builtins).allMatch(t -> t.getDescription() != null && !t.getDescription().isBlank());
        assertThat(builtins).allMatch(t -> t.getOwnerId() == null);
    }
}
```

- [ ] **Step 5: 跑测试，确认失败**

Run: `cd server && ./mvnw -q -Dtest=ToolMapperIT test`
Expected: 编译/运行失败（Tool/ToolMapper 未定义或迁移未生效）——先确认红。

- [ ] **Step 6: 补齐让测试通过**（Step 2/3 的类 + Step 1 迁移已具备后重跑）

Run: `cd server && ./mvnw -q -Dtest=ToolMapperIT test`
Expected: Tests run: 1, Failures: 0, Errors: 0（看 Tests run 行，勿 grep BUILD）。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/resources/db/migration/V23__create_tool.sql \
        server/src/main/java/com/hify/tool/entity/Tool.java \
        server/src/main/java/com/hify/tool/mapper/ToolMapper.java \
        server/src/test/java/com/hify/tool/mapper/ToolMapperIT.java
git commit -m "feat(tool): 工具注册表建表(V23)+Tool实体/Mapper+播种2内置工具"
```

---

## Task 2: 内置工具执行器（BuiltinTool + HttpRequestTool + CodeExecutorTool）

**Files:**
- Create: `server/src/main/java/com/hify/tool/service/builtin/BuiltinTool.java`
- Create: `server/src/main/java/com/hify/tool/service/builtin/HttpRequestTool.java`
- Create: `server/src/main/java/com/hify/tool/service/builtin/CodeExecutorTool.java`
- Test: `server/src/test/java/com/hify/tool/service/builtin/HttpRequestToolTest.java`
- Test: `server/src/test/java/com/hify/tool/service/builtin/CodeExecutorToolTest.java`

**Interfaces:**
- Consumes: `OutboundHttpClient`、`SandboxClient`、`OutboundResponse`、`SandboxResult`（infra）。
- Produces: `BuiltinTool`（`String name()`、`String inputSchema()`、`String execute(String argsJson)`——**永不抛异常，失败返回错误文本**）；两个 `@Component` 实现，name 分别为 `http_request`、`code_executor`。

- [ ] **Step 1: 写 BuiltinTool 接口**

```java
package com.hify.tool.service.builtin;

/**
 * 内置工具执行器契约（仅 tool 模块内部使用，不进 api）。
 * name 须与 tool 表 builtin 行的 name 一致；inputSchema 为工具入参的 JSON Schema（模型据此构造参数）。
 * execute 接收模型给出的 JSON 参数字符串，返回给模型的结果文本——**任何失败都返回错误文本，绝不抛异常**
 * （让模型自行恢复/致歉，不中断整轮 Agent 循环）。
 */
public interface BuiltinTool {

    String name();

    String inputSchema();

    String execute(String argsJson);
}
```

- [ ] **Step 2: 写 HttpRequestTool 的失败测试**

```java
package com.hify.tool.service.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.infra.outbound.OutboundResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class HttpRequestToolTest {

    private final OutboundHttpClient httpClient = Mockito.mock(OutboundHttpClient.class);
    private final HttpRequestTool tool = new HttpRequestTool(httpClient, new ObjectMapper());

    @Test
    void name_与_schema_齐备() {
        assertThat(tool.name()).isEqualTo("http_request");
        assertThat(tool.inputSchema()).contains("\"method\"").contains("\"url\"").contains("required");
    }

    @Test
    void 成功请求_返回状态码与响应体() {
        when(httpClient.send(eq("GET"), eq("https://api.example.com/x"), any(), any()))
                .thenReturn(new OutboundResponse(200, "{\"a\":1}", Map.of()));
        String out = tool.execute("{\"method\":\"GET\",\"url\":\"https://api.example.com/x\"}");
        assertThat(out).contains("200").contains("{\"a\":1}");
    }

    @Test
    void 方法不在白名单_返回错误文本_不抛() {
        String out = tool.execute("{\"method\":\"TRACE\",\"url\":\"https://x\"}");
        assertThat(out).contains("不支持").contains("TRACE");
    }

    @Test
    void 缺少url_返回错误文本() {
        String out = tool.execute("{\"method\":\"GET\"}");
        assertThat(out).contains("url");
    }

    @Test
    void 网络失败_BizException_被吞成错误文本() {
        when(httpClient.send(any(), any(), any(), any()))
                .thenThrow(new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "HTTP 请求失败：timeout"));
        String out = tool.execute("{\"method\":\"GET\",\"url\":\"https://x\"}");
        assertThat(out).contains("错误").contains("timeout");
    }

    @Test
    void 参数非json_返回错误文本() {
        String out = tool.execute("not-json");
        assertThat(out).contains("错误");
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd server && ./mvnw -q -Dtest=HttpRequestToolTest test`
Expected: 编译失败（HttpRequestTool 未定义）。

- [ ] **Step 4: 写 HttpRequestTool 实现**

```java
package com.hify.tool.service.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.infra.outbound.OutboundResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 内置 HTTP 工具：复用 infra 的 OutboundHttpClient（自带 SSRF/双超时/Redirect.NEVER/响应截断）。
 * method 白名单在本工具校验；任何失败以错误文本返回给模型，不抛（不中断 Agent 循环）。
 */
@Component
public class HttpRequestTool implements BuiltinTool {

    private static final Set<String> ALLOWED = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");
    private static final String SCHEMA = """
            {"type":"object","properties":{
              "method":{"type":"string","description":"HTTP 方法：GET/POST/PUT/DELETE/PATCH"},
              "url":{"type":"string","description":"完整 http/https URL"},
              "headers":{"type":"object","description":"可选，请求头键值对（字符串→字符串）"},
              "body":{"type":"string","description":"可选，请求体字符串"}},
              "required":["method","url"]}""";

    private final OutboundHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpRequestTool(OutboundHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "http_request";
    }

    @Override
    public String inputSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(String argsJson) {
        JsonNode args;
        try {
            args = objectMapper.readTree(argsJson);
        } catch (Exception e) {
            return "错误：参数不是合法 JSON：" + e.getMessage();
        }
        String method = args.path("method").asText("").toUpperCase();
        if (!ALLOWED.contains(method)) {
            return "错误：不支持的 HTTP 方法：" + method;
        }
        String url = args.path("url").asText("");
        if (url.isBlank()) {
            return "错误：缺少 url 参数";
        }
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode h = args.get("headers");
        if (h != null && h.isObject()) {
            h.fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
        }
        String body = args.hasNonNull("body") ? args.get("body").asText() : null;
        try {
            OutboundResponse resp = httpClient.send(method, url, headers, body);
            return "HTTP " + resp.status() + "\n" + resp.body();
        } catch (BizException e) {
            return "错误：" + e.getMessage();
        }
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd server && ./mvnw -q -Dtest=HttpRequestToolTest test`
Expected: Tests run: 6, Failures: 0, Errors: 0。

- [ ] **Step 6: 写 CodeExecutorTool 的失败测试**

```java
package com.hify.tool.service.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.outbound.SandboxClient;
import com.hify.infra.outbound.SandboxResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class CodeExecutorToolTest {

    private final SandboxClient sandbox = Mockito.mock(SandboxClient.class);
    private final CodeExecutorTool tool = new CodeExecutorTool(sandbox, new ObjectMapper());

    @Test
    void name_与_schema_齐备() {
        assertThat(tool.name()).isEqualTo("code_executor");
        assertThat(tool.inputSchema()).contains("\"code\"").contains("required");
    }

    @Test
    void 执行成功_返回outputs的json() {
        when(sandbox.run(eq("def main():\n    return {'answer': 4}"), any()))
                .thenReturn(new SandboxResult(true, Map.of("answer", 4), null));
        String out = tool.execute("{\"code\":\"def main():\\n    return {'answer': 4}\"}");
        assertThat(out).contains("answer").contains("4");
    }

    @Test
    void 沙箱业务失败_ok为false_返回错误文本_不抛() {
        when(sandbox.run(any(), any()))
                .thenReturn(new SandboxResult(false, Map.of(), "执行出错：ZeroDivisionError"));
        String out = tool.execute("{\"code\":\"def main():\\n    return {'x': 1/0}\"}");
        assertThat(out).contains("错误").contains("ZeroDivisionError");
    }

    @Test
    void 沙箱不可达_BizException_被吞成错误文本() {
        when(sandbox.run(any(), any()))
                .thenThrow(new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "沙箱调用失败：refused"));
        String out = tool.execute("{\"code\":\"def main():\\n    return {}\"}");
        assertThat(out).contains("错误").contains("refused");
    }

    @Test
    void 缺code_返回错误文本() {
        assertThat(tool.execute("{}")).contains("code");
    }
}
```

- [ ] **Step 7: 跑测试确认失败**

Run: `cd server && ./mvnw -q -Dtest=CodeExecutorToolTest test`
Expected: 编译失败（CodeExecutorTool 未定义）。

- [ ] **Step 8: 写 CodeExecutorTool 实现**

```java
package com.hify.tool.service.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.infra.outbound.SandboxClient;
import com.hify.infra.outbound.SandboxResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 内置代码工具：复用 infra 的 SandboxClient（隔离容器/双超时/并发信号量/输出上限）。
 * Agent 直接把值写进代码，inputs 传空 map（沙箱契约：用户码定义 main(**inputs) 返回 dict，此处 main()）。
 * 任何失败以错误文本返回给模型，不抛（不中断 Agent 循环）。
 */
@Component
public class CodeExecutorTool implements BuiltinTool {

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "code":{"type":"string","description":"完整 Python 源码；必须定义无参函数 main() 并返回一个 dict"}},
              "required":["code"]}""";

    private final SandboxClient sandbox;
    private final ObjectMapper objectMapper;

    public CodeExecutorTool(SandboxClient sandbox, ObjectMapper objectMapper) {
        this.sandbox = sandbox;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "code_executor";
    }

    @Override
    public String inputSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(String argsJson) {
        String code;
        try {
            JsonNode args = objectMapper.readTree(argsJson);
            code = args.path("code").asText("");
        } catch (Exception e) {
            return "错误：参数不是合法 JSON：" + e.getMessage();
        }
        if (code.isBlank()) {
            return "错误：缺少 code 参数";
        }
        try {
            SandboxResult r = sandbox.run(code, Map.of());
            if (!r.ok()) {
                return "错误：" + r.error();
            }
            return objectMapper.writeValueAsString(r.outputs());
        } catch (BizException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            return "错误：结果序列化失败：" + e.getMessage();
        }
    }
}
```

- [ ] **Step 9: 跑测试确认通过**

Run: `cd server && ./mvnw -q -Dtest=CodeExecutorToolTest test`
Expected: Tests run: 5, Failures: 0, Errors: 0。

- [ ] **Step 10: 提交**

```bash
git add server/src/main/java/com/hify/tool/service/builtin/ \
        server/src/test/java/com/hify/tool/service/builtin/
git commit -m "feat(tool): 2内置工具执行器(HTTP复用OutboundHttpClient/代码复用SandboxClient)"
```

---

## Task 3: `ToolRegistry` + `BuiltinToolCallback` + `ToolFacade`（暴露 ToolCallback）

**Files:**
- Create: `server/src/main/java/com/hify/tool/service/BuiltinToolCallback.java`
- Create: `server/src/main/java/com/hify/tool/service/ToolRegistry.java`
- Create: `server/src/main/java/com/hify/tool/api/ToolFacade.java`
- Create: `server/src/main/java/com/hify/tool/service/ToolFacadeImpl.java`
- Modify: `server/src/main/java/com/hify/tool/package-info.java`（补 Spring AI 例外注释）
- Test: `server/src/test/java/com/hify/tool/service/ToolRegistryTest.java`

**Interfaces:**
- Consumes: `ToolMapper`、`Tool`（Task 1）；`BuiltinTool` 集合（Task 2）；Spring AI `ToolCallback`/`ToolDefinition`/`DefaultToolDefinition`。
- Produces: `ToolFacade.getBuiltinToolCallbacks()` → `List<ToolCallback>`（供 conversation Task 6/7 消费）。

- [ ] **Step 1: 写 BuiltinToolCallback**

```java
package com.hify.tool.service;

import com.hify.tool.service.builtin.BuiltinTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 把一个 {@link BuiltinTool} 适配成 Spring AI {@link ToolCallback}。
 * 直接实现接口，避开 FunctionToolCallback 的泛型入参反序列化——execute 自己解析 JSON 参数字符串。
 */
public class BuiltinToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final BuiltinTool tool;

    public BuiltinToolCallback(ToolDefinition definition, BuiltinTool tool) {
        this.definition = definition;
        this.tool = tool;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return tool.execute(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }
}
```

- [ ] **Step 2: 写 ToolRegistry**

```java
package com.hify.tool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.builtin.BuiltinTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具注册表：读 DB 中 enabled 的工具行，对 source=builtin 的行按 name 绑定内置执行器，
 * 产出 Spring AI ToolCallback 列表。ToolDefinition 的 name/description 取自 DB 行（统一来源，
 * 与 T3/T4 openapi/mcp 一致），inputSchema 取自 builtin 执行器（builtin 特有，代码为准）。
 * T1 只处理 builtin；openapi/mcp 行 T3/T4 再补分支（本轮无此类行）。
 */
@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ToolMapper toolMapper;
    private final Map<String, BuiltinTool> builtinByName;

    public ToolRegistry(ToolMapper toolMapper, List<BuiltinTool> builtinTools) {
        this.toolMapper = toolMapper;
        this.builtinByName = builtinTools.stream()
                .collect(Collectors.toMap(BuiltinTool::name, Function.identity()));
    }

    /** 全部 enabled 的内置工具 → ToolCallback（找不到执行器的行跳过并告警）。 */
    public List<ToolCallback> getBuiltinToolCallbacks() {
        List<Tool> rows = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .eq(Tool::getSource, "builtin")
                .eq(Tool::getEnabled, true)
                .orderByAsc(Tool::getName));
        List<ToolCallback> callbacks = new ArrayList<>(rows.size());
        for (Tool row : rows) {
            BuiltinTool exec = builtinByName.get(row.getName());
            if (exec == null) {
                log.warn("内置工具行无对应执行器，跳过 name={}", row.getName());
                continue;
            }
            ToolDefinition def = DefaultToolDefinition.builder()
                    .name(row.getName())
                    .description(row.getDescription())
                    .inputSchema(exec.inputSchema())
                    .build();
            callbacks.add(new BuiltinToolCallback(def, exec));
        }
        return callbacks;
    }
}
```

- [ ] **Step 3: 写 ToolFacade + 实现**

`api/ToolFacade.java`：

```java
package com.hify.tool.api;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * tool 模块对外门面。
 * 例外：本模块 Facade 允许在签名中使用 Spring AI 类型（ToolCallback）——把 provider 的同类例外扩展到 tool
 * （拍板结论，见 code-organization.md §2「api/」例外条目）。工具对 Agent 透明的天然载体即 ToolCallback。
 */
public interface ToolFacade {

    /** 取全部 enabled 的内置工具 ToolCallback（T1：HTTP + 代码）。T2 追加 per-app 选择方法。 */
    List<ToolCallback> getBuiltinToolCallbacks();
}
```

`service/ToolFacadeImpl.java`：

```java
package com.hify.tool.service;

import com.hify.tool.api.ToolFacade;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ToolFacadeImpl implements ToolFacade {

    private final ToolRegistry registry;

    public ToolFacadeImpl(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<ToolCallback> getBuiltinToolCallbacks() {
        return registry.getBuiltinToolCallbacks();
    }
}
```

- [ ] **Step 4: 补 package-info 例外注释**

`server/src/main/java/com/hify/tool/package-info.java` 顶部 javadoc 增一行（`allowedDependencies` **不改**，Spring AI 是库不是模块）：

```java
/**
 * tool —— 工具注册表、内置工具、OpenAPI 工具、MCP 接入。
 *
 * <p>依赖白名单（code-organization.md 第 1 节）：仅 common、infra。
 * 例外：本模块 Facade 允许在签名中使用 Spring AI 类型（ToolCallback）——与 provider 暴露 ChatClient 同理。
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "infra"}
)
package com.hify.tool;
```

- [ ] **Step 5: 写 ToolRegistry 单测（先失败）**

用 mock ToolMapper + 真实/假的 BuiltinTool，验证由行产出 callback：

```java
package com.hify.tool.service;

import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.builtin.BuiltinTool;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ToolRegistryTest {

    private final ToolMapper toolMapper = Mockito.mock(ToolMapper.class);

    private static BuiltinTool fake(String name, String result) {
        return new BuiltinTool() {
            public String name() { return name; }
            public String inputSchema() { return "{\"type\":\"object\"}"; }
            public String execute(String argsJson) { return result; }
        };
    }

    private static Tool row(String name, String desc, boolean enabled) {
        Tool t = new Tool();
        t.setName(name); t.setDescription(desc); t.setSource("builtin"); t.setEnabled(enabled);
        return t;
    }

    @Test
    void 由builtin行绑定执行器产出callback() {
        when(toolMapper.selectList(any())).thenReturn(List.of(
                row("http_request", "HTTP 工具说明", true)));
        ToolRegistry registry = new ToolRegistry(toolMapper,
                List.of(fake("http_request", "OK"), fake("code_executor", "X")));

        List<ToolCallback> cbs = registry.getBuiltinToolCallbacks();

        assertThat(cbs).hasSize(1);
        ToolCallback cb = cbs.get(0);
        assertThat(cb.getToolDefinition().name()).isEqualTo("http_request");
        assertThat(cb.getToolDefinition().description()).isEqualTo("HTTP 工具说明");
        assertThat(cb.call("{}")).isEqualTo("OK");
    }

    @Test
    void 行无对应执行器则跳过_不抛() {
        when(toolMapper.selectList(any())).thenReturn(List.of(row("ghost", "无执行器", true)));
        ToolRegistry registry = new ToolRegistry(toolMapper, List.of(fake("http_request", "OK")));
        assertThat(registry.getBuiltinToolCallbacks()).isEmpty();
    }
}
```

- [ ] **Step 6: 跑测试确认失败 → 补齐 → 通过**

Run: `cd server && ./mvnw -q -Dtest=ToolRegistryTest test`
Expected: 先失败（类未定义），补齐 Step 1-3 后 Tests run: 2, Failures: 0。

- [ ] **Step 7: 跑模块边界测试，确认 Modulith 仍绿**

Run: `cd server && ./mvnw -q -Dtest=ModularityTests test`
Expected: Tests run: 1, Failures: 0（tool 新增 api/service 未越界；ToolCallback 是库类型不触发模块校验）。

- [ ] **Step 8: 提交**

```bash
git add server/src/main/java/com/hify/tool/ \
        server/src/test/java/com/hify/tool/service/ToolRegistryTest.java
git commit -m "feat(tool): ToolRegistry+ToolFacade暴露Spring AI ToolCallback(方案A,例外扩展到tool)"
```

---

## Task 4: app 模块 `agentEnabled` 开关

**Files:**
- Modify: `server/src/main/java/com/hify/app/api/dto/AppConfig.java`
- Modify: `server/src/main/java/com/hify/app/api/AppRuntimeView.java`
- Modify: `server/src/main/java/com/hify/app/service/AppFacadeImpl.java:44-48`
- Modify: `server/src/main/java/com/hify/app/service/AppService.java`（3 处 `new AppConfig(null)`：约 L65、L124、L232）
- Test: `server/src/test/java/com/hify/app/service/AppFacadeImplTest.java`（新建或并入既有 app 测试）

**Interfaces:**
- Produces: `AppConfig(String systemPrompt, boolean agentEnabled)`；`AppRuntimeView(Long appId, Long modelId, String systemPrompt, List<Long> datasetIds, boolean agentEnabled)`——供 conversation Task 7 分流。

- [ ] **Step 1: 改 AppConfig（新增字段向后兼容）**

```java
package com.hify.app.api.dto;

/**
 * 对话型应用的运行配置（jsonb 落库）。systemPrompt=系统提示词（可空）；
 * agentEnabled=是否启用 Agent 工具调用（默认 false；T1 由 tool 注册表全启用内置工具，per-app 选择留 T2）。
 * 跨模块 record：conversation 读 app 时消费。新增字段向后兼容（老 jsonb 缺 agentEnabled → Jackson 置 false）。
 */
public record AppConfig(String systemPrompt, boolean agentEnabled) {
}
```

- [ ] **Step 2: 改 AppRuntimeView**

```java
package com.hify.app.api;

import java.util.List;

/**
 * 应用运行时视图（跨模块）：conversation 取它发起对话。
 * modelId 必非空；systemPrompt 为人设（可空）；datasetIds 恒非 null（无绑定=空列表）；
 * agentEnabled=true 时 conversation 走 Agent 工具调用循环（T1）。
 */
public record AppRuntimeView(Long appId, Long modelId, String systemPrompt,
                             List<Long> datasetIds, boolean agentEnabled) {
}
```

- [ ] **Step 3: 改 AppFacadeImpl 投影**

`AppFacadeImpl.java` 第 44-48 行改为：

```java
        String systemPrompt = app.getConfig() == null ? null : app.getConfig().systemPrompt();
        boolean agentEnabled = app.getConfig() != null && app.getConfig().agentEnabled();
        List<Long> datasetIds = relMapper.selectList(new LambdaQueryWrapper<AppDatasetRel>()
                        .eq(AppDatasetRel::getAppId, app.getId()).orderByAsc(AppDatasetRel::getId))
                .stream().map(AppDatasetRel::getDatasetId).toList();
        return Optional.of(new AppRuntimeView(app.getId(), app.getModelId(), systemPrompt, datasetIds, agentEnabled));
```

- [ ] **Step 4: 修 AppService 三处构造点**

`AppService.java` 把 3 处 `new AppConfig(null)` 改为 `new AppConfig(null, false)`（约 L65、L124、L232）。用 grep 定位全部：

Run: `cd server && grep -rn "new AppConfig(null)" src/main/java`
逐处改为 `new AppConfig(null, false)`。改后再 grep 应为 0 处。

- [ ] **Step 5: 全量 grep AppRuntimeView 构造点，补齐入参**

Run: `cd server && grep -rn "new AppRuntimeView(" src/main/java src/test/java`
预期只有 `AppFacadeImpl`（Step 3 已改）+ 可能的测试桩。所有构造点补上 `, agentEnabled`（测试桩传 `false`/`true`）。

- [ ] **Step 6: 写测试（先失败）**

```java
package com.hify.app.service;

// 复用既有 app 单测/IT 基类（若已有 AppFacadeImplTest 则并入两个 case）
// 断言：config.agentEnabled=true 的 chat 应用 → view.agentEnabled()=true；
//       config 无 agentEnabled（老数据 {} / systemPrompt-only）→ view.agentEnabled()=false。
```

（具体桩法照本仓库既有 app 测试；核心断言两条：agentEnabled 透传 true、缺省 false。）

- [ ] **Step 7: 跑测试确认通过 + 回归 app 既有测试**

Run: `cd server && ./mvnw -q -Dtest='App*Test,App*IT' test`
Expected: 全绿（含被改签名的构造点编译通过）。

- [ ] **Step 8: 提交**

```bash
git add server/src/main/java/com/hify/app/ server/src/test/java/com/hify/app/
git commit -m "feat(app): AppConfig/AppRuntimeView 增 agentEnabled 开关(jsonb零迁移,向后兼容)"
```

---

## Task 5: `message.tool_calls` 映射（轨迹落库地基）

**Files:**
- Create: `server/src/main/java/com/hify/conversation/dto/MessageToolCall.java`
- Create: `server/src/main/java/com/hify/conversation/config/MessageToolCallsTypeHandler.java`
- Modify: `server/src/main/java/com/hify/conversation/entity/Message.java`
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationStore.java`（appendAssistant 重载）
- Test: `server/src/test/java/com/hify/conversation/config/MessageToolCallsTypeHandlerTest.java`

**Interfaces:**
- Produces: `MessageToolCall(String name, String args, String result)`；`Message.getToolCalls()/setToolCalls(List<MessageToolCall>)`；`ConversationStore.appendAssistant(..., List<MessageSource> sources, List<MessageToolCall> toolCalls)`（9 参重载；原 8 参委托传 `List.of()`）。

- [ ] **Step 1: 写 MessageToolCall record**

```java
package com.hify.conversation.dto;

/**
 * Agent 单次工具调用轨迹（落 message.tool_calls jsonb 数组的元素）。
 * name=工具名；args=模型给出的 JSON 参数字符串；result=工具返回给模型的结果文本（成功或错误文本）。
 * 非 Agent 消息该列恒 []（DB 默认保证）。
 */
public record MessageToolCall(String name, String args, String result) {
}
```

- [ ] **Step 2: 写 TypeHandler（照 MessageSourcesTypeHandler 手法）**

```java
package com.hify.conversation.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.conversation.dto.MessageToolCall;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * message.tool_calls（jsonb）↔ {@code List<MessageToolCall>}（照 MessageSourcesTypeHandler）。
 * 写出包 PGobject(type=jsonb)；读入空/空白兜底 List.of()，保证字段不为 null。
 * 实体需 @TableName(autoResultMap=true)（Message 已具备）。
 */
public class MessageToolCallsTypeHandler extends BaseTypeHandler<List<MessageToolCall>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<MessageToolCall>> LIST_TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<MessageToolCall> parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        try {
            obj.setValue(MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("序列化 message.tool_calls 失败", e);
        }
        ps.setObject(i, obj);
    }

    @Override
    public List<MessageToolCall> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<MessageToolCall> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<MessageToolCall> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<MessageToolCall> parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new SQLException("反序列化 message.tool_calls 失败", e);
        }
    }
}
```

- [ ] **Step 3: 改 Message 实体，映射 tool_calls**

在 `sources` 字段后新增（更新类注释「本轮恒空」那句）：

```java
    @TableField(typeHandler = com.hify.conversation.config.MessageToolCallsTypeHandler.class)
    private List<com.hify.conversation.dto.MessageToolCall> toolCalls;
```

并加 getter/setter：

```java
    public List<com.hify.conversation.dto.MessageToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<com.hify.conversation.dto.MessageToolCall> toolCalls) { this.toolCalls = toolCalls; }
```

（import 与既有风格一致即可；`@TableField` 的 `column` 默认按驼峰转 `tool_calls`，与列名一致，无需显式指定。）

- [ ] **Step 4: ConversationStore.appendAssistant 加 9 参重载**

保留现有 8 参方法，改为委托新 9 参方法（新增 `toolCalls` 参数，落 `m.setToolCalls(...)`）：

```java
    // 原 8 参签名保持不变，委托：普通聊天无工具轨迹
    public Message appendAssistant(Long conversationId, String content, int promptTokens, int completionTokens,
                                   Long userId, Long appId, Long modelId, List<MessageSource> sources) {
        return appendAssistant(conversationId, content, promptTokens, completionTokens,
                userId, appId, modelId, sources, List.of());
    }

    @Transactional
    public Message appendAssistant(Long conversationId, String content, int promptTokens, int completionTokens,
                                   Long userId, Long appId, Long modelId, List<MessageSource> sources,
                                   List<MessageToolCall> toolCalls) {
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setRole(MessageRole.ASSISTANT.value());
        m.setContent(content);
        m.setPromptTokens(promptTokens);
        m.setCompletionTokens(completionTokens);
        m.setSources(sources);
        m.setToolCalls(toolCalls);
        messageMapper.insert(m);
        Conversation touch = new Conversation();
        touch.setId(conversationId);
        conversationMapper.updateById(touch);
        publisher.publishEvent(new TokenUsedEvent(userId, appId, modelId, promptTokens, completionTokens));
        return m;
    }
```

（原 8 参方法上的 `@Transactional` 移除——事务在被委托的 9 参方法上；委托方法本身无库操作。新增 `import com.hify.conversation.dto.MessageToolCall;`）

- [ ] **Step 5: 写 TypeHandler round-trip 测试（先失败）**

```java
package com.hify.conversation.config;

import com.hify.conversation.dto.MessageToolCall;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.PreparedStatement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;

class MessageToolCallsTypeHandlerTest {

    private final MessageToolCallsTypeHandler handler = new MessageToolCallsTypeHandler();

    @Test
    void 空json读为空列表() throws Exception {
        var rs = Mockito.mock(java.sql.ResultSet.class);
        Mockito.when(rs.getString("tool_calls")).thenReturn("[]");
        assertThat(handler.getNullableResult(rs, "tool_calls")).isEmpty();
    }

    @Test
    void null读为空列表() throws Exception {
        var rs = Mockito.mock(java.sql.ResultSet.class);
        Mockito.when(rs.getString("tool_calls")).thenReturn(null);
        assertThat(handler.getNullableResult(rs, "tool_calls")).isEmpty();
    }

    @Test
    void 写出包成jsonb并可回读() throws Exception {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        handler.setNonNullParameter(ps, 1,
                List.of(new MessageToolCall("http_request", "{\"url\":\"x\"}", "HTTP 200")), null);
        Mockito.verify(ps).setObject(anyInt(), any(org.postgresql.util.PGobject.class));
    }
}
```

- [ ] **Step 6: 跑测试确认失败 → 补齐 → 通过**

Run: `cd server && ./mvnw -q -Dtest=MessageToolCallsTypeHandlerTest test`
Expected: Tests run: 3, Failures: 0。

- [ ] **Step 7: 回归 conversation 既有测试（appendAssistant 调用方未受影响）**

Run: `cd server && ./mvnw -q -Dtest='Conversation*Test,ConversationStore*' test`
Expected: 全绿（原 8 参调用点仍编译/通过）。

- [ ] **Step 8: 提交**

```bash
git add server/src/main/java/com/hify/conversation/dto/MessageToolCall.java \
        server/src/main/java/com/hify/conversation/config/MessageToolCallsTypeHandler.java \
        server/src/main/java/com/hify/conversation/entity/Message.java \
        server/src/main/java/com/hify/conversation/service/ConversationStore.java \
        server/src/test/java/com/hify/conversation/config/MessageToolCallsTypeHandlerTest.java
git commit -m "feat(conversation): 映射 message.tool_calls(TypeHandler)+appendAssistant轨迹重载"
```

---

## Task 6: `AgentChatService`（手动 tool-calling 循环）

**Files:**
- Create: `server/src/main/java/com/hify/conversation/config/AgentProperties.java`
- Modify: `server/src/main/java/com/hify/conversation/config/ConversationConfig.java`
- Create: `server/src/main/java/com/hify/conversation/service/AgentReply.java`
- Create: `server/src/main/java/com/hify/conversation/service/AgentChatService.java`
- Test: `server/src/test/java/com/hify/conversation/service/AgentChatServiceTest.java`

**Interfaces:**
- Consumes: `ChatInvoker.toMessages(String,List<Message>)`（同包可见）；Spring AI `ToolCallback`/`ChatClient`/`ChatResponse`/`AssistantMessage`/`ToolResponseMessage`/`ToolCallingChatOptions`。
- Produces: `AgentReply(String content, int promptTokens, int completionTokens, List<MessageToolCall> toolCalls)`；`AgentChatService.run(ChatClient, String systemPrompt, List<Message> window, List<ToolCallback> toolCallbacks)`→`AgentReply`；单次模型调用抽成可覆写的 `callModel(...)` 作测试缝。

- [ ] **Step 1: 写 AgentProperties + 注册**

`AgentProperties.java`：

```java
package com.hify.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 循环配置（hify.agent.*）。maxToolIterations：一轮对话内允许的模型调用次数上限
 * （防 Agent 级联/异常循环刷爆 Token 账单）。
 */
@ConfigurationProperties(prefix = "hify.agent")
public record AgentProperties(int maxToolIterations) {
}
```

`ConversationConfig.java` 改注解：

```java
@Configuration
@EnableConfigurationProperties({ConversationProperties.class, AgentProperties.class})
public class ConversationConfig {
}
```

- [ ] **Step 2: 写 AgentReply record**

```java
package com.hify.conversation.service;

import com.hify.conversation.dto.MessageToolCall;

import java.util.List;

/** Agent 循环产出：终答文本 + 累计 token（各轮之和）+ 工具调用轨迹。 */
public record AgentReply(String content, int promptTokens, int completionTokens,
                         List<MessageToolCall> toolCalls) {
}
```

- [ ] **Step 3: 写 AgentChatService 的失败测试**

测试用子类覆写 `callModel` 返回脚本化 `ChatResponse`，验证循环编排（不 mock ChatClient 流式 API）：

```java
package com.hify.conversation.service;

import com.hify.conversation.config.AgentProperties;
import com.hify.conversation.entity.Message;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentChatServiceTest {

    // 假工具：记录被调用，返回固定结果
    private static ToolCallback fakeTool(String name, String result) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
            }
            public String call(String toolInput) { return result; }
            public String call(String toolInput, ToolContext ctx) { return result; }
        };
    }

    private static ChatResponse assistantWithToolCall(String toolName, String args) {
        AssistantMessage am = new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall("id1", "function", toolName, args)));
        return new ChatResponse(List.of(new Generation(am)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(3, 2, 5)).build());
    }

    private static ChatResponse finalAnswer(String text) {
        AssistantMessage am = new AssistantMessage(text);
        return new ChatResponse(List.of(new Generation(am)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(4, 6, 10)).build());
    }

    // 用脚本化 callModel 的子类
    private static AgentChatService withScript(int maxIter, Deque<ChatResponse> script) {
        return new AgentChatService(new ChatInvoker(), new AgentProperties(maxIter)) {
            @Override
            ChatResponse callModel(ChatClient c, List<org.springframework.ai.chat.messages.Message> msgs,
                                   List<ToolCallback> cbs) {
                return script.poll();
            }
        };
    }

    @Test
    void 一轮工具调用后给出终答_轨迹与累计token正确() {
        Deque<ChatResponse> script = new ArrayDeque<>(List.of(
                assistantWithToolCall("http_request", "{\"url\":\"x\"}"),
                finalAnswer("根据接口，答案是 42")));
        AgentChatService svc = withScript(5, script);

        AgentReply reply = svc.run(null, "你是助手",
                List.of(userMsg("查一下")), List.of(fakeTool("http_request", "HTTP 200\n{...}")));

        assertThat(reply.content()).isEqualTo("根据接口，答案是 42");
        assertThat(reply.toolCalls()).hasSize(1);
        assertThat(reply.toolCalls().get(0).name()).isEqualTo("http_request");
        assertThat(reply.toolCalls().get(0).result()).contains("HTTP 200");
        assertThat(reply.promptTokens()).isEqualTo(3 + 4);   // 两轮累计
        assertThat(reply.completionTokens()).isEqualTo(2 + 6);
    }

    @Test
    void 无工具调用直接终答_轨迹为空() {
        Deque<ChatResponse> script = new ArrayDeque<>(List.of(finalAnswer("你好")));
        AgentReply reply = withScript(5, script).run(null, "", List.of(userMsg("hi")), List.of());
        assertThat(reply.content()).isEqualTo("你好");
        assertThat(reply.toolCalls()).isEmpty();
    }

    @Test
    void 超步数上限_返回提示且不无限循环() {
        // 每轮都要求调工具，永不终答
        Deque<ChatResponse> script = new ArrayDeque<>();
        for (int i = 0; i < 10; i++) script.add(assistantWithToolCall("http_request", "{}"));
        AgentReply reply = withScript(3, script)
                .run(null, "", List.of(userMsg("loop")), List.of(fakeTool("http_request", "R")));
        assertThat(reply.content()).contains("步数上限");
        assertThat(reply.toolCalls()).hasSize(3);   // 恰好 maxIter 轮
    }

    @Test
    void 未知工具名_结果记为不存在_不抛() {
        Deque<ChatResponse> script = new ArrayDeque<>(List.of(
                assistantWithToolCall("ghost_tool", "{}"),
                finalAnswer("好的")));
        AgentReply reply = withScript(5, script)
                .run(null, "", List.of(userMsg("x")), List.of(fakeTool("http_request", "R")));
        assertThat(reply.toolCalls().get(0).result()).contains("不存在");
    }

    private static Message userMsg(String content) {
        Message m = new Message();
        m.setRole("user");
        m.setContent(content);
        return m;
    }
}
```

> 说明：`DefaultUsage`/`ChatResponseMetadata.builder()`/`Generation(AssistantMessage)`/`AssistantMessage.ToolCall(id,type,name,arguments)` 为 Spring AI 1.0.1 公开构造（已核实存在）；若某构造入参微调，按 IDE 提示对齐，语义不变。

- [ ] **Step 4: 跑测试确认失败**

Run: `cd server && ./mvnw -q -Dtest=AgentChatServiceTest test`
Expected: 编译失败（AgentChatService 未定义）。

- [ ] **Step 5: 写 AgentChatService 实现**

```java
package com.hify.conversation.service;

import com.hify.conversation.config.AgentProperties;
import com.hify.conversation.dto.MessageToolCall;
import com.hify.conversation.entity.Message;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent 同步编排：手动 tool-calling 循环（internalToolExecutionEnabled(false)，读回工具调用直接执行）。
 * 不带 @Transactional——这里是模型调用 + 工具外呼（外部 IO）。token 各轮累计，落库时由 ConversationStore
 * 在事务内发一次 TokenUsedEvent。单次模型调用抽成 callModel 作测试缝（参照 ChatInvoker：真实 IO 不单测）。
 */
@Service
public class AgentChatService {

    private final ChatInvoker chatInvoker;
    private final AgentProperties props;

    public AgentChatService(ChatInvoker chatInvoker, AgentProperties props) {
        this.chatInvoker = chatInvoker;
        this.props = props;
    }

    public AgentReply run(ChatClient chatClient, String systemPrompt, List<Message> window,
                          List<ToolCallback> toolCallbacks) {
        Map<String, ToolCallback> byName = toolCallbacks.stream()
                .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), Function.identity(), (a, b) -> a));
        List<org.springframework.ai.chat.messages.Message> msgs = chatInvoker.toMessages(systemPrompt, window);
        List<MessageToolCall> trace = new ArrayList<>();
        int promptTokens = 0;
        int completionTokens = 0;
        String lastText = "";

        for (int i = 0; i < props.maxToolIterations(); i++) {
            ChatResponse resp = callModel(chatClient, msgs, toolCallbacks);
            Usage u = resp.getMetadata() != null ? resp.getMetadata().getUsage() : null;
            if (u != null) {
                promptTokens += u.getPromptTokens() != null ? u.getPromptTokens() : 0;
                completionTokens += u.getCompletionTokens() != null ? u.getCompletionTokens() : 0;
            }
            AssistantMessage assistant = resp.getResult().getOutput();
            lastText = assistant.getText() != null ? assistant.getText() : "";

            if (!resp.hasToolCalls()) {
                return new AgentReply(lastText, promptTokens, completionTokens, List.copyOf(trace));
            }

            msgs.add(assistant);
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                ToolCallback cb = byName.get(call.name());
                String result;
                if (cb == null) {
                    result = "错误：工具不存在：" + call.name();
                } else {
                    try {
                        result = cb.call(call.arguments());
                    } catch (RuntimeException ex) {
                        result = "错误：工具执行失败：" + ex.getMessage();
                    }
                }
                trace.add(new MessageToolCall(call.name(), call.arguments(), result));
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
            }
            msgs.add(new ToolResponseMessage(responses));
        }

        String content = (lastText.isBlank() ? "" : lastText + "\n\n")
                + "（已达工具调用步数上限，回答可能不完整）";
        return new AgentReply(content, promptTokens, completionTokens, List.copyOf(trace));
    }

    /** 单次模型调用（挂工具、禁自动执行）——真实外部 IO，测试子类覆写此方法注入脚本化响应。 */
    ChatResponse callModel(ChatClient chatClient, List<org.springframework.ai.chat.messages.Message> msgs,
                           List<ToolCallback> toolCallbacks) {
        return chatClient.prompt()
                .messages(msgs)
                .options(ToolCallingChatOptions.builder()
                        .toolCallbacks(toolCallbacks)
                        .internalToolExecutionEnabled(false)
                        .build())
                .call()
                .chatResponse();
    }
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `cd server && ./mvnw -q -Dtest=AgentChatServiceTest test`
Expected: Tests run: 4, Failures: 0。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/java/com/hify/conversation/config/AgentProperties.java \
        server/src/main/java/com/hify/conversation/config/ConversationConfig.java \
        server/src/main/java/com/hify/conversation/service/AgentReply.java \
        server/src/main/java/com/hify/conversation/service/AgentChatService.java \
        server/src/test/java/com/hify/conversation/service/AgentChatServiceTest.java
git commit -m "feat(conversation): AgentChatService手动tool-calling循环(内部禁自动执行/步数上限/轨迹累计)"
```

---

## Task 7: 接线 ConversationService（agent 分流 + SSE 守卫）

**Files:**
- Modify: `server/src/main/java/com/hify/conversation/constant/ConversationError.java`（加 17002）
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java`（注入 ToolFacade+AgentChatService；send 分流；sendStream 守卫）
- Modify: `server/src/main/java/com/hify/conversation/package-info.java`（确认 allowedDependencies 含 `tool::api`——已有则不改）
- Test: `server/src/test/java/com/hify/conversation/service/ConversationServiceAgentRoutingTest.java`（可选，若既有 ConversationService 测试基座支持则并入）

**Interfaces:**
- Consumes: `AppRuntimeView.agentEnabled()`（Task 4）；`ToolFacade.getBuiltinToolCallbacks()`（Task 3）；`AgentChatService.run(...)`（Task 6）；`ConversationStore.appendAssistant(...9参)`（Task 5）。

- [ ] **Step 1: 确认 conversation 依赖白名单含 tool::api**

Run: `cd server && grep -n "tool::api" src/main/java/com/hify/conversation/package-info.java`
Expected: 已存在（code-organization §5.1 示例即含 `tool::api`）。若缺失则加入 `allowedDependencies`。

- [ ] **Step 2: 加错误码 17002**

`ConversationError.java` 在枚举里追加（注意逗号/分号）：

```java
    /** 应用不存在/非对话型/已停用/未绑定模型——无法发起对话。 */
    APP_NOT_RUNNABLE(17001, HttpStatus.BAD_REQUEST, "应用未绑定可用模型或已停用，无法发起对话"),

    /** Agent 应用暂不支持流式对话（T1 只做同步，流式留 T2）。 */
    AGENT_STREAM_UNSUPPORTED(17002, HttpStatus.BAD_REQUEST, "Agent 应用暂不支持流式对话");
```

- [ ] **Step 3: 注入依赖 + send 分流 + sendStream 守卫**

`ConversationService` 构造函数注入 `ToolFacade toolFacade`、`AgentChatService agentChatService`（加字段、构造参数、赋值）。

`send(...)` 方法在取得 `chatClient` 后按 `app.agentEnabled()` 分流（Agent 路径不做知识检索，sources 空）：

```java
            ChatClient chatClient = providerFacade.getChatClient(app.modelId());
            Message saved;
            if (app.agentEnabled()) {
                AgentReply reply = agentChatService.run(chatClient, app.systemPrompt(), turn.window(),
                        toolFacade.getBuiltinToolCallbacks());
                saved = store.appendAssistant(cid, reply.content(), reply.promptTokens(), reply.completionTokens(),
                        current.userId(), appId, app.modelId(), List.of(), reply.toolCalls());
            } else {
                Augmented aug = augmentWithKnowledge(app, content);
                LlmReply reply = chatInvoker.invoke(chatClient, aug.prompt(), turn.window());
                saved = store.appendAssistant(cid, reply.content(), reply.promptTokens(), reply.completionTokens(),
                        current.userId(), appId, app.modelId(), aug.sources());
            }
            return new SendMessageResponse(cid, toView(saved));
```

（`import com.hify.tool.api.ToolFacade;`；`AgentReply`/`AgentChatService` 同包无需 import。原 `Augmented aug = ...` 那行移进 else 分支。）

`sendStream(...)` 在 `findRunnableChatApp` 之后、`openTurn` 之前加守卫：

```java
        AppRuntimeView app = appFacade.findRunnableChatApp(appId)
                .orElseThrow(() -> new BizException(ConversationError.APP_NOT_RUNNABLE));
        if (app.agentEnabled()) {
            throw new BizException(ConversationError.AGENT_STREAM_UNSUPPORTED);
        }
```

- [ ] **Step 4: 写分流测试（先失败）**

用 mock（AppFacade 返回 agentEnabled=true/false、ToolFacade、AgentChatService、ProviderFacade、ConversationStore）断言：
- `send` 且 `agentEnabled=true` → 调 `agentChatService.run`、`store.appendAssistant(9参, toolCalls)`，不调 `chatInvoker.invoke`。
- `send` 且 `agentEnabled=false` → 调 `chatInvoker.invoke`，不调 `agentChatService.run`。
- `sendStream` 且 `agentEnabled=true` → 抛 `BizException(AGENT_STREAM_UNSUPPORTED)`（`StepVerifier` 或直接断言抛出，取决于是否在订阅前抛——本实现是同步抛，测试直接 `assertThatThrownBy`）。

（照本仓库既有 `ConversationServiceTest` 的 mock 装配风格；若无则新建，Mockito 装 6 个依赖。）

- [ ] **Step 5: 跑测试确认通过 + 回归**

Run: `cd server && ./mvnw -q -Dtest='ConversationService*' test`
Expected: 全绿。

- [ ] **Step 6: 全量编译 + 模块边界 + 架构测试**

Run: `cd server && ./mvnw -q -Dtest='ModularityTests,LayerRulesTest' test`
Expected: 全绿（conversation→tool::api 合法；DTO 未 import entity；事务只在 service）。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/java/com/hify/conversation/constant/ConversationError.java \
        server/src/main/java/com/hify/conversation/service/ConversationService.java \
        server/src/test/java/com/hify/conversation/service/
git commit -m "feat(conversation): 接线Agent分流(send走AgentChatService)+SSE守卫(17002)"
```

---

## Task 8: 配置 + 文档回写 + self-check 验收

**Files:**
- Modify: `server/src/main/resources/application.yml`（加 `hify.agent.max-tool-iterations`）
- Modify: `docs/architecture/code-organization.md`（Facade Spring AI 例外扩展到 tool）
- Modify: `docs/architecture/data-model.md`（tool 表落地确认）
- Modify: `docs/self-check.md`（T1 验收记录）

- [ ] **Step 1: 加配置**

`application.yml` 的 `hify:` 下新增（与 `conversation:`/`outbound:`/`sandbox:` 同级）：

```yaml
  agent:
    max-tool-iterations: 5   # 一轮对话内模型调用次数上限，防 Agent 异常循环刷 Token
```

- [ ] **Step 2: 回写 code-organization.md**

§2「api/」层职责处，把「例外：`provider` 的 Facade 允许…Spring AI 类型」一句扩为「`provider` 与 `tool` 的 Facade 允许…（provider：ChatClient/EmbeddingModel；tool：ToolCallback）」；§4.2 同处补一句。

- [ ] **Step 3: 回写 data-model.md**

确认 `tool` 表条目与 V23 实际列一致（本轮最小列集：name/description/source/enabled/spec/owner_id + BaseEntity；app_tool_rel/mcp_server 标注留 T2/T4）。

- [ ] **Step 4: 全量构建 + 全测试**

Run: `cd server && ./mvnw -q clean test`
Expected: 全绿（看 Tests run 汇总，无 Failures/Errors）。

- [ ] **Step 5: 起服务做手动 self-check（curl，按 spec §1 六步）**

前置：`docker compose up -d`（postgres + sandbox + server；沙箱本地联调见 [[workflow-code-node-merged]] 运维手册）。重启=重打包换进程（见 [[retrieval-threshold-tuned]]）。

1. 用 SQL 翻开关（选一个已存在的 chat 应用 id）：
   ```sql
   update app set config = jsonb_set(config, '{agentEnabled}', 'true')
   where id = <某chat应用id>;
   ```
2. 登录拿 token（admin 已 seed，见 [[admin-account-seeded]]），同步发消息，提问需外呼：
   ```bash
   curl -s -X POST http://localhost:8080/api/v1/conversation/messages \
     -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
     -d '{"appId":"<id>","content":"用 http_request 工具获取 https://httpbin.org/json 并总结其中的 slideshow 标题"}'
   ```
   > 注意别用内网/元数据地址（SSRF 会拦，见 [[workflow-w3b-merged]]）。
3. 断言返回 content 基于工具结果；查库：
   ```sql
   select role, left(content,40), tool_calls from message
   where conversation_id = <cid> order by id desc limit 2;
   ```
   assistant 行 `tool_calls` 应含 `[{"name":"http_request",...}]`。
4. 代码工具：`content` 提问「用 code_executor 计算 1 到 100 的和」，验证轨迹含 `code_executor` 且答案 5050。
5. 非 agent 应用（另一个 agentEnabled=false）回归普通聊天，行为不变。
6. 对 agent 应用打流式端点，确认返 17002：
   ```bash
   curl -s -X POST http://localhost:8080/api/v1/conversation/messages/stream \
     -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
     -d '{"appId":"<agent-id>","content":"hi"}'
   # 期望 code=17002
   ```

- [ ] **Step 6: 记录 self-check 结果入档**

把 Step 5 的实际请求/响应/库查询证据追加到 `docs/self-check.md`（本轮小节：环境、6 步逐条结果、遗留项）。诚实记录：失败就写失败与输出。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/resources/application.yml docs/architecture/code-organization.md \
        docs/architecture/data-model.md docs/self-check.md
git commit -m "chore(agent): T1配置外化(max-tool-iterations)+文档回写(Facade例外/tool表)+self-check入档"
```

---

## 自审（写完计划对照 spec）

**Spec coverage**：
- §5.1 tool 表/实体/Mapper/播种 → Task 1 ✅；内置执行器 → Task 2 ✅；ToolRegistry/BuiltinToolCallback/ToolFacade + 例外 → Task 3 ✅。
- §5.2 AgentChatService 手动循环/累计 token/轨迹 → Task 6 ✅；分流 + SSE 守卫(17002) → Task 7 ✅；message.tool_calls 映射 → Task 5 ✅。
- §5.3 AppConfig/AppRuntimeView agentEnabled + 映射 → Task 4 ✅。
- §5.4 配置外化 → Task 8 Step 1 ✅。
- §7 错误处理（工具失败回文本/超上限/17002/非 agent 不变/事务纪律）→ Task 2（吞异常）+ Task 6（超上限、未知工具）+ Task 7（守卫、分流）✅。
- §8 测试（单测各件 + Testcontainers tool 表 + 手动 self-check）→ Task 1/2/3/5/6/7 单测 + Task 8 self-check ✅。
- §10 文档回写 → Task 8 Step 2/3 ✅。

**Placeholder scan**：无 TBD/TODO；测试桩说明处（Task 4 Step 6、Task 7 Step 4）指向"照既有测试基座"，因其 mock 装配依赖本仓库既有测试风格，故给出断言语义与要点而非杜撰基类——执行时以仓库现有同类测试为准。

**Type consistency**：`appendAssistant` 9 参签名（Task 5 定义 / Task 7 调用）一致；`AgentReply`/`AgentChatService.run`/`callModel` 签名（Task 6 定义 / Task 7 调用）一致；`AppRuntimeView` 5 参（Task 4 定义 / Task 7 读 `agentEnabled()`）一致；`ToolFacade.getBuiltinToolCallbacks()`（Task 3 定义 / Task 7 调用）一致；`BuiltinTool.name()/inputSchema()/execute()`（Task 2 定义 / Task 3 消费）一致。

## 执行顺序与依赖

Task 1 → 2 → 3（tool 模块自足，可先整体完成并单独验证）；Task 4、5 相互独立（app / conversation 各自）；Task 6 依赖 5（MessageToolCall）；Task 7 依赖 3/4/5/6 全部；Task 8 最后。建议严格按 1→8 顺序执行，每 Task 独立可测可提交。
