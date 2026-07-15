# T4a MCP 工具接入（后端）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让管理员注册远程 MCP 服务器（`source=mcp`），系统连过去发现其工具并存快照，注册表读时展开成多个 Spring AI `ToolCallback`，对 Agent 透明。

**Architecture:** 沿用 T3a 的 Model D——1 个 MCP 服务器 = `tool` 表 1 行（`spec jsonb` 存 url/transport/加密鉴权头/工具快照/discoveredAt），`ToolRegistry` 读时按快照展开成 N 个 `McpToolCallback`（**热路径零网络开销**）；模型真调工具时才建连（`McpClientFactory` → initialize → callTool → close）。`tool.spec` 一列两形状经新引入的 `ToolSpec` 多态接口（`kind` 字段）承载。零加表，conversation/workflow 零改动。

**Tech Stack:** Spring Boot 3.5 + Spring AI 1.0.1（只用 `ToolCallback`/`ToolDefinition`）+ MyBatis-Plus + **MCP Java SDK 0.12.1（`io.modelcontextprotocol.sdk:mcp`，不引 spring-ai-mcp / starter，Spring AI 版本不动）** + JUnit5/Mockito/AssertJ。

设计依据：`docs/superpowers/specs/2026-07-15-agent-tool-t4a-mcp-backend-design.md`（8 项拍板决策）。

## Global Constraints

- **只支持远程 HTTP**：`streamable_http`（默认）/ `sse`（兼容）。**不支持 stdio**，不得引入任何子进程 spawn 代码。
- **不引入 `spring-ai-starter-mcp-client` / `spring-ai-mcp`；不改 `<spring-ai.version>`（保持 1.0.1）**。只加 `io.modelcontextprotocol.sdk:mcp:0.12.1`。
- MCP 出站**只经 `McpClientFactory`**（禁自建 MCP 客户端）；建连前必过 `SsrfValidator`（禁内网/回环/元数据）；必须 `followRedirects(NEVER)`。
- 所有超时**外化**到 `application.yml`（`hify.tool.mcp.*`），不硬编码。
- admin 路由复用 `/api/v1/admin/tool/tools`（带模块段 `tool`），`SecurityConfig` 的 `hasRole("ADMIN")` 统一拦 `/api/v1/admin/**`，控制器类上不加注解。**不新开路由前缀**。
- 一期不用 PATCH：刷新用动作子资源 `POST .../refresh`；PUT 全量替换；DELETE 返 `Result<Void>`。
- Long 一律 JSON 序列化为字符串（infra 全局 Jackson 已配）；集合响应永不为 null（`[]`）；null 对象字段照常输出；时间用 `OffsetDateTime`。
- 错误码优先复用通用段：`CommonError.PARAM_INVALID`(10001)、`NOT_FOUND`(10005)、`CONFLICT`(10006)；**仅新增 `ToolError.MCP_CONNECT_FAILED`(13002/400)**。
- **10001 与 13002 的边界**：url 不合法 / SSRF 拒绝 / 域名不可解析 → `10001`（`SsrfValidator` 原样抛出，**不得吞掉重包**）；连不上 / 握手超时 / 鉴权被拒 / 非法 MCP 响应 / listTools 失败 → `13002`。
- 凭据只存密文（`SecretCipher`），任何响应 DTO **不回传明文鉴权值**。
- `ToolCallback` 契约：`call()` 任何失败**返回错误文本、绝不抛**（不中断 Agent 循环），与 `BuiltinToolCallback`/`OpenApiToolCallback` 一致。
- **不改旧 Flyway 迁移**；本轮新增 `V25`。
- 无 Lombok，getter/setter 手写；实体继承 `com.hify.common.BaseEntity`；jsonb 字段需 `@TableName(autoResultMap=true)` + `@TableField(typeHandler=...)`。
- **不改 T3b 已上线的前端契约**：`type` 缺省即 `openapi`；响应 DTO 只加字段不改老字段；`ToolAdminResponse.operationCount` 保留原名。
- ArchUnit：DTO 禁止 import entity；tool 模块依赖白名单为「无」（只可依赖 common/infra）。

### 三个已实测确认的陷阱（务必照做，别自行"优化"）

1. **transport builder 的 endpoint 默认值会拼错 URL**：`HttpClientStreamableHttpTransport.Builder` 的 `endpoint` 默认 `"/mcp"`，`HttpClientSseClientTransport.Builder` 的 `sseEndpoint` 默认 `"/sse"`（已反编译确认）。若把完整 URL 当 baseUri 传入，会拼成 `https://host/mcp/mcp`。**必须把 URL 拆成 origin + path 分别喂给 `builder(origin)` 与 `.endpoint(path)` / `.sseEndpoint(path)`。**
2. **`McpSchema.Tool.inputSchema()` 返回类型化的 `JsonSchema` record，不是字符串**，而 `ToolDefinition.inputSchema(...)` 要字符串 ⇒ 发现时须 `mapper.writeValueAsString(tool.inputSchema())`。
3. **序列化 schema / spec 的私有 `ObjectMapper` 必须配 `NON_NULL` + 注册 `JavaTimeModule`**：`JsonSchema` 有 `defs`/`definitions`/`additionalProperties` 可空字段（`ALWAYS` 会把 `"defs":null` 塞进发给模型的 schema，纯噪声）；`McpToolSpec.discoveredAt` 是 `OffsetDateTime`，不注册 `JavaTimeModule` 直接序列化失败。

---

## File Structure

**新建（主代码）**
- `server/src/main/java/com/hify/tool/service/ToolSpec.java` — 多态接口（`kind` 分派 openapi/mcp）
- `server/src/main/java/com/hify/tool/config/ToolSpecTypeHandler.java` — `tool.spec` jsonb ↔ `ToolSpec`
- `server/src/main/java/com/hify/tool/config/McpProperties.java` — `hify.tool.mcp.*` 超时
- `server/src/main/java/com/hify/tool/service/mcp/McpToolSpec.java` — spec 的 mcp 形状 record
- `server/src/main/java/com/hify/tool/service/mcp/McpClientFactory.java` — 造 `McpSyncClient`（安全闸门收口）
- `server/src/main/java/com/hify/tool/service/mcp/McpToolDiscoverer.java` — 连远端 listTools → 快照
- `server/src/main/java/com/hify/tool/service/mcp/DiscoveredMcpTools.java` — 发现中间结果 record
- `server/src/main/java/com/hify/tool/service/mcp/McpToolCallback.java` — 单 MCP 工具 → Spring AI ToolCallback
- `server/src/main/java/com/hify/tool/dto/McpToolView.java` — 详情/预览里的工具摘要
- `server/src/main/resources/db/migration/V25__tool_spec_add_kind.sql` — 存量 openapi 行补 `kind`

**修改（主代码）**
- `server/pom.xml` — 加 `io.modelcontextprotocol.sdk:mcp:0.12.1` + `<mcp-sdk.version>`
- `server/src/main/resources/application.yml` — 加 `hify.tool.mcp.*`
- `server/src/main/java/com/hify/tool/service/openapi/OpenApiToolSpec.java` — `implements ToolSpec`
- `server/src/main/java/com/hify/tool/entity/Tool.java` — `spec` 类型 `OpenApiToolSpec`→`ToolSpec`，typeHandler 换
- `server/src/main/java/com/hify/tool/service/ToolAdminService.java` — 4 处 spec cast（L69/L99/L193-195）；`assertOpenApi`→`assertNotBuiltin`；mcp 分支（create/update/refresh）
- `server/src/main/java/com/hify/tool/service/ToolRegistry.java` — L109 cast；新增 `source=mcp` 展开分支；注入 `McpClientFactory`
- `server/src/main/java/com/hify/tool/constant/ToolError.java` — 加 `MCP_CONNECT_FAILED`(13002)
- `server/src/main/java/com/hify/tool/controller/AdminToolController.java` — `preview` 改传整个 request（Task 6，随签名原子改）；加 `POST /{id}/refresh`（Task 7）
- `server/src/main/java/com/hify/tool/dto/CreateToolRequest.java`、`UpdateToolRequest.java`、`PreviewToolRequest.java` — 加 `type/url/transport`，`specText` 改条件必填
- `server/src/main/java/com/hify/tool/dto/ToolAdminDetailResponse.java`、`ToolPreviewResponse.java` — 加 mcp 字段

**删除**
- `server/src/main/java/com/hify/tool/config/OpenApiToolSpecTypeHandler.java`（被 `ToolSpecTypeHandler` 取代）

**测试**
- 改名 `server/src/test/java/com/hify/tool/config/OpenApiToolSpecTypeHandlerTest.java` → `ToolSpecTypeHandlerTest.java`（+ 存量无 kind 兼容测试）
- 改 `server/src/test/java/com/hify/tool/service/ToolAdminServiceTest.java`（getSpec 处加 cast）
- `server/src/test/java/com/hify/tool/service/ToolRegistryOpenApiTest.java`（ToolRegistry 构造器参数增加）
- `server/src/test/java/com/hify/tool/service/ToolRegistryTest.java`（ToolRegistry 构造器参数增加）
- `server/src/test/java/com/hify/tool/service/ToolFacadeImplTest.java`（ToolRegistry 构造器参数增加）
- 新建 `server/src/test/java/com/hify/tool/service/mcp/FakeMcpServer.java` — 最小 MCP 服务器桩（测试基建）
- 新建 `server/src/test/java/com/hify/tool/service/mcp/TestSsrf.java` — 放行版 SsrfValidator（测试基建）
- 新建 `server/src/test/java/com/hify/tool/service/mcp/McpClientFactoryTest.java`
- 新建 `server/src/test/java/com/hify/tool/service/mcp/McpToolDiscovererTest.java`
- 新建 `server/src/test/java/com/hify/tool/service/mcp/McpToolCallbackTest.java`
- 改 `server/src/test/java/com/hify/tool/controller/AdminToolControllerTest.java`（Task 6：`ToolPreviewResponse` 加第三参；Task 7：加 refresh 端点测试）
- 新建 `server/src/test/java/com/hify/tool/service/ToolRegistryMcpTest.java`
- 新建 `server/src/test/java/com/hify/tool/service/ToolAdminServiceMcpTest.java`

> **枚举既有调用点时不要用 `new Xxx(` 做 grep 前缀**——本仓库多处用全限定名
> （`new com.hify.tool.dto.UpdateToolRequest(`），带 `new ` 的 grep 会**静默漏掉**它们。
> 用类名本身搜（`grep -rn "UpdateToolRequest("`）。写本计划时正因此漏了
> `AdminToolControllerTest` 与 3 处 `UpdateToolRequest`。

---

## Task 1: `ToolSpec` 多态 + V25 + T3a 适配

**为什么先做**：这是唯一会动到**存量数据**的改动，隔离风险；做完 T3a 现有测试必须全绿，证明没弄坏已上线的东西。

**Files:**
- Create: `server/src/main/java/com/hify/tool/service/ToolSpec.java`
- Create: `server/src/main/java/com/hify/tool/config/ToolSpecTypeHandler.java`
- Create: `server/src/main/resources/db/migration/V25__tool_spec_add_kind.sql`
- Delete: `server/src/main/java/com/hify/tool/config/OpenApiToolSpecTypeHandler.java`
- Modify: `server/src/main/java/com/hify/tool/service/openapi/OpenApiToolSpec.java`
- Modify: `server/src/main/java/com/hify/tool/entity/Tool.java`
- Modify: `server/src/main/java/com/hify/tool/service/ToolAdminService.java`（L69、L99、L193-195）
- Modify: `server/src/main/java/com/hify/tool/service/ToolRegistry.java`（L109）
- Test: `server/src/test/java/com/hify/tool/config/ToolSpecTypeHandlerTest.java`（由 `OpenApiToolSpecTypeHandlerTest.java` 改名而来）
- Test: `server/src/test/java/com/hify/tool/service/ToolAdminServiceTest.java`（L66/67、L144、L161 加 cast）

**Interfaces:**
- Produces: `com.hify.tool.service.ToolSpec`（空标记接口，Jackson 多态载体，`property = "kind"`）；`OpenApiToolSpec implements ToolSpec`；`Tool.getSpec()/setSpec()` 类型变为 `ToolSpec`；`ToolSpecTypeHandler extends BaseTypeHandler<ToolSpec>`。
- 后续 Task 3 会往 `@JsonSubTypes` 里加 `McpToolSpec` 一行。

- [x] **Step 1: 写失败测试——存量无 `kind` 的老 JSON 必须仍能解成 `OpenApiToolSpec`**

创建 `server/src/test/java/com/hify/tool/config/ToolSpecTypeHandlerTest.java`（先删除旧的 `OpenApiToolSpecTypeHandlerTest.java`）：

```java
package com.hify.tool.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.tool.service.ToolSpec;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSpecTypeHandlerTest {

    private final ObjectMapper mapper = ToolSpecTypeHandler.specMapper();

    @Test
    void roundTrip_openApi_preservesFieldsAndWritesKind() throws Exception {
        OpenApiToolSpec spec = new OpenApiToolSpec(
                "https://api.example.com",
                List.of(new OpenApiToolSpec.AuthHeader("X-API-Key", "ENC")),
                List.of(new OpenApiToolSpec.Operation("getPet", "GET", "/pets/{id}", "查",
                        "{\"type\":\"object\"}",
                        List.of(new OpenApiToolSpec.Param("id", "path", true)))),
                "raw");

        String json = mapper.writeValueAsString(spec);
        assertThat(json).contains("\"kind\":\"openapi\"");

        ToolSpec back = mapper.readValue(json, ToolSpec.class);
        assertThat(back).isInstanceOf(OpenApiToolSpec.class);
        OpenApiToolSpec o = (OpenApiToolSpec) back;
        assertThat(o.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(o.authHeaders().get(0).valueEnc()).isEqualTo("ENC");
        assertThat(o.operations().get(0).parameters().get(0).in()).isEqualTo("path");
    }

    /** T3a 时期落库的行没有 kind——defaultImpl 必须让它们仍能读出来，否则一上线就炸本地已有数据。 */
    @Test
    void legacyJsonWithoutKind_stillDeserializesAsOpenApi() throws Exception {
        String legacy = """
                {"baseUrl":"https://api.example.com",
                 "authHeaders":[{"name":"X-API-Key","valueEnc":"ENC"}],
                 "operations":[{"opName":"getPet","method":"GET","pathTemplate":"/pets/{id}",
                                "description":"查","inputSchema":"{}","parameters":[]}],
                 "rawSpec":"raw"}
                """;

        ToolSpec back = mapper.readValue(legacy, ToolSpec.class);

        assertThat(back).isInstanceOf(OpenApiToolSpec.class);
        assertThat(((OpenApiToolSpec) back).baseUrl()).isEqualTo("https://api.example.com");
    }
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `cd server && mvn -q -Dtest=ToolSpecTypeHandlerTest test`
Expected: 编译失败——`ToolSpec` / `ToolSpecTypeHandler.specMapper()` 不存在。

> **注意**（memory `mvn-quiet-verify-pitfall`）：`-q` 会静音成功输出。**判定结果看是否有 ERROR/FAIL 段落，不要 grep "BUILD SUCCESS"**。

- [x] **Step 3: 写 `ToolSpec` 接口**

创建 `server/src/main/java/com/hify/tool/service/ToolSpec.java`：

```java
package com.hify.tool.service;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.hify.tool.service.openapi.OpenApiToolSpec;

/**
 * tool.spec(jsonb) 的多态载体：openapi 与 mcp 两种形状共用一列，靠 kind 分派。
 *
 * <p>不用 sealed：无 JPMS 时 sealed 的实现类必须与接口同包，而两个实现分处 service/openapi 与
 * service/mcp 子包，挪包会牵动 T3a 一堆 import，收益不抵成本（T4a spec 决策 7）。
 *
 * <p>defaultImpl：T3a 时期落库的 openapi 行 jsonb 里没有 kind，缺 kind 时按 openapi 解。
 * V25 迁移已给存量补齐，此处是兜底（双保险，别删）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind",
        defaultImpl = OpenApiToolSpec.class)
@JsonSubTypes({
        @JsonSubTypes.Type(value = OpenApiToolSpec.class, name = "openapi")
})
public interface ToolSpec {}
```

- [x] **Step 4: `OpenApiToolSpec` 实现接口**

修改 `server/src/main/java/com/hify/tool/service/openapi/OpenApiToolSpec.java` 的声明行：

```java
package com.hify.tool.service.openapi;

import com.hify.tool.service.ToolSpec;

import java.util.List;

/** tool.spec(jsonb) 映射：一条 openapi 注册的自包含执行描述。凭据只存密文 valueEnc。 */
public record OpenApiToolSpec(
        String baseUrl,
        List<AuthHeader> authHeaders,
        List<Operation> operations,
        String rawSpec) implements ToolSpec {

    public record AuthHeader(String name, String valueEnc) {}

    public record Operation(
            String opName,
            String method,
            String pathTemplate,
            String description,
            String inputSchema,
            List<Param> parameters) {}

    public record Param(String name, String in, boolean required) {}
}
```

- [x] **Step 5: 写 `ToolSpecTypeHandler`，删旧 handler**

创建 `server/src/main/java/com/hify/tool/config/ToolSpecTypeHandler.java`：

```java
package com.hify.tool.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hify.tool.service.ToolSpec;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * tool.spec(jsonb) ↔ {@link ToolSpec}（kind 分派 openapi/mcp）。builtin 行 spec 为 null（读空→null）。
 * 实体需 autoResultMap=true。
 *
 * <p>用私有 ObjectMapper 而非注入 Spring 那个：spec 是<b>库内存储</b>格式，不该被对外 JSON 的全局策略
 * （api-standards §4 的 JsonInclude.ALWAYS）牵着走——NON_NULL 让 jsonb 干净；JavaTimeModule 是
 * McpToolSpec.discoveredAt(OffsetDateTime) 的硬需求，不注册直接序列化失败。
 */
public class ToolSpecTypeHandler extends BaseTypeHandler<ToolSpec> {

    private static final ObjectMapper MAPPER = specMapper();

    /** 供测试与 mcp 侧 schema 序列化复用的同款 mapper。 */
    public static ObjectMapper specMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, ToolSpec parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        try {
            obj.setValue(MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("序列化 tool.spec 失败", e);
        }
        ps.setObject(i, obj);
    }

    @Override
    public ToolSpec getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public ToolSpec getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public ToolSpec getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private ToolSpec parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, ToolSpec.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("反序列化 tool.spec 失败", e);
        }
    }
}
```

删除旧文件：

```bash
rm server/src/main/java/com/hify/tool/config/OpenApiToolSpecTypeHandler.java
rm -f server/src/test/java/com/hify/tool/config/OpenApiToolSpecTypeHandlerTest.java
```

> ⚠️ **从这里到 Step 9 结束，代码库处于"编译不过"的中间状态，这是本 Task 的固有性质，不是出错。**
> `Tool.getSpec()` 的返回类型一改，`ToolAdminService`(4 处)、`ToolRegistry`(1 处)、
> `ToolAdminServiceTest`(4 处) 会同时编译不过——类型迁移在编译上是**原子的**，中间不存在可编译的
> 快照。**所以 Step 6-9 之间不要跑测试**，一路改完到 Step 10 再跑绿。
> （TDD 的"红"已经在 Step 2 观察过了：测试因 `ToolSpec` 不存在而编译失败。）

- [x] **Step 6: `Tool` 实体换类型**

修改 `server/src/main/java/com/hify/tool/entity/Tool.java`——把 import、字段、getter/setter 四处的 `OpenApiToolSpec` 换成 `ToolSpec`，typeHandler 换成 `ToolSpecTypeHandler`：

```java
package com.hify.tool.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;
import com.hify.tool.config.ToolSpecTypeHandler;
import com.hify.tool.service.ToolSpec;

/**
 * 工具注册表 {@code tool} 映射实体。source 分 builtin/openapi/mcp；
 * name 为模型寻址标识与内置执行器绑定键。openapi/mcp 行 spec(jsonb) 存执行描述，builtin 恒空。
 */
@TableName(value = "tool", autoResultMap = true)
public class Tool extends BaseEntity {

    private String name;
    private String description;
    private String source;
    private Boolean enabled;
    private Long ownerId;
    @TableField(typeHandler = ToolSpecTypeHandler.class)
    private ToolSpec spec;

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

    public ToolSpec getSpec() { return spec; }
    public void setSpec(ToolSpec spec) { this.spec = spec; }
}
```

- [x] **Step 7: 适配 `ToolAdminService` 的 4 处 spec 用法**

修改 `server/src/main/java/com/hify/tool/service/ToolAdminService.java`。

`get(Long id)` 方法（原 L67-82）——把 `OpenApiToolSpec spec = row.getSpec();` + null 判断换成 `instanceof` 模式匹配（null 与非 openapi 都走空分支，行为与原来一致）：

```java
    public ToolAdminDetailResponse get(Long id) {
        Tool row = require(id);
        if (!(row.getSpec() instanceof OpenApiToolSpec spec)) {
            return new ToolAdminDetailResponse(row.getId(), row.getName(), row.getDescription(), row.getSource(),
                    Boolean.TRUE.equals(row.getEnabled()), null, List.of(), List.of(), null);
        }
        List<OperationView> operations = spec.operations() == null ? List.of() : spec.operations().stream()
                .map(op -> new OperationView(op.opName(), op.method(), op.pathTemplate(), op.description()))
                .toList();
        List<String> authHeaderNames = spec.authHeaders() == null ? List.of() : spec.authHeaders().stream()
                .map(OpenApiToolSpec.AuthHeader::name)
                .toList();
        return new ToolAdminDetailResponse(row.getId(), row.getName(), row.getDescription(), row.getSource(),
                Boolean.TRUE.equals(row.getEnabled()), spec.baseUrl(), operations, authHeaderNames, spec.rawSpec());
    }
```

`update(...)` 方法里的 `buildSpecForUpdate` 调用（原 L99）——update 已被 `assertOpenApi` 挡住只剩 openapi 行，在调用点 cast：

```java
        row.setSpec(buildSpecForUpdate(req.specText(), req.authHeaders(),
                row.getSpec() instanceof OpenApiToolSpec old ? old : null));
```

`toResponse(Tool row)`（原 L192-198）：

```java
    private ToolAdminResponse toResponse(Tool row) {
        Integer count = row.getSpec() instanceof OpenApiToolSpec s && s.operations() != null
                ? s.operations().size()
                : null;
        return new ToolAdminResponse(row.getId(), row.getName(), row.getDescription(), row.getSource(),
                Boolean.TRUE.equals(row.getEnabled()), count, row.getOwnerId(), row.getCreateTime(), row.getUpdateTime());
    }
```

- [x] **Step 8: 适配 `ToolRegistry` 的 spec 用法**

修改 `server/src/main/java/com/hify/tool/service/ToolRegistry.java` 的 `expandOpenApi`（原 L108-113 头部）：

```java
    private List<ToolCallback> expandOpenApi(Tool row) {
        if (!(row.getSpec() instanceof OpenApiToolSpec spec) || spec.operations() == null) {
            log.warn("openapi 工具行 spec 为空，跳过 id={}", row.getId());
            return List.of();
        }
        // 以下不变
```

- [x] **Step 9: 适配 `ToolAdminServiceTest` 的 4 处断言**

修改 `server/src/test/java/com/hify/tool/service/ToolAdminServiceTest.java`——`row.getSpec()` 现在返回 `ToolSpec`，断言处加 cast。L66/67 所在测试：

```java
        assertThat(((OpenApiToolSpec) row.getSpec()).authHeaders().get(0).valueEnc()).isEqualTo("ENC");
        assertThat(((OpenApiToolSpec) row.getSpec()).rawSpec()).isEqualTo("SPEC");
```

L144、L161 同款（`saved.getValue().getSpec()` 处）：

```java
        assertThat(((OpenApiToolSpec) saved.getValue().getSpec()).authHeaders().get(0).valueEnc()).isEqualTo("ENC");
```

```java
        assertThat(((OpenApiToolSpec) saved.getValue().getSpec()).authHeaders().get(0).valueEnc()).isEqualTo("NEWENC");
```

> `setSpec(new OpenApiToolSpec(...))` 处**无需改动**——`OpenApiToolSpec` 已 implements `ToolSpec`，直接兼容。

- [x] **Step 10: 运行测试确认转绿（到这里编译才重新成立）**

Run: `cd server && mvn -q -Dtest=ToolSpecTypeHandlerTest test`
Expected: 2 个测试通过，无 ERROR/FAIL 段落。

> 若仍报 `cannot find symbol`，说明 Step 6-9 有遗漏：用
> `grep -rn "getSpec()\|OpenApiToolSpecTypeHandler" server/src --include=*.java` 找出还没适配的地方。

- [x] **Step 11: 写 V25 迁移**

创建 `server/src/main/resources/db/migration/V25__tool_spec_add_kind.sql`：

```sql
-- V25：给存量 openapi 行的 spec 补 kind 标记（配合 T4a 引入的 ToolSpec 多态：Jackson 按 kind 分派
-- openapi/mcp 两种形状）。T3a 起落库的 openapi 行 jsonb 无 kind；ToolSpec 的 defaultImpl 已能兜底，
-- 本迁移让数据自身自洽，不长期依赖隐性兜底。builtin 行 spec 为 null，不受影响。
-- 用 jsonb_exists(spec,'kind') 而非 `spec ? 'kind'`：? 在 JDBC 语境与占位符同形，绕开省得踩坑。
update tool
   set spec = spec || '{"kind":"openapi"}'::jsonb
 where source = 'openapi'
   and spec is not null
   and not jsonb_exists(spec, 'kind');
```

- [x] **Step 12: 跑 tool 模块全部测试 + 架构测试，确认 T3a 没被弄坏**

Run: `cd server && mvn -q -Dtest='Tool*Test,ModularityTests,LayerRules*' test`
Expected: 全绿，无 ERROR/FAIL 段落。特别确认 `ToolAdminServiceTest`、`ToolRegistryOpenApiTest`、`OpenApiSpecParserTest`、`OpenApiToolCallbackTest` 均通过。

- [x] **Step 13: Commit**

```bash
git add server/src/main/java/com/hify/tool server/src/test/java/com/hify/tool server/src/main/resources/db/migration/V25__tool_spec_add_kind.sql
git commit -m "refactor(tool): 引入 ToolSpec 多态承载 spec 一列两形状(kind + defaultImpl + V25 补齐存量)"
```

---

## Task 2: MCP SDK 依赖 + `McpProperties` + `McpClientFactory`

**Files:**
- Modify: `server/pom.xml`
- Modify: `server/src/main/resources/application.yml`
- Create: `server/src/main/java/com/hify/tool/config/McpProperties.java`
- Create: `server/src/main/java/com/hify/tool/service/mcp/McpClientFactory.java`
- Test: `server/src/test/java/com/hify/tool/service/mcp/McpClientFactoryTest.java`

**Interfaces:**
- Consumes: `com.hify.infra.outbound.SsrfValidator#validate(String host)`（内网抛 `BizException(10001)`）；其包私有构造器 `SsrfValidator(Function<String, InetAddress[]> resolver)` 供测试注入假解析。
- Produces:
  - `McpProperties`：`getConnectTimeoutMs()` / `getRequestTimeoutMs()` / `getInitializationTimeoutMs()`（均 `int`）。
  - `McpClientFactory#create(String url, String transport, Map<String,String> plainHeaders) → McpSyncClient`（调用方负责 close）。
  - 常量：`McpClientFactory.TRANSPORT_STREAMABLE_HTTP = "streamable_http"`、`TRANSPORT_SSE = "sse"`。

- [x] **Step 1: 加 MCP SDK 依赖**

修改 `server/pom.xml`：在 `<properties>` 段加版本（与 `<spring-ai.version>` 相邻）：

```xml
        <mcp-sdk.version>0.12.1</mcp-sdk.version>
```

在 `<dependencies>` 段加依赖（放在 swagger-parser 依赖之后）：

```xml
        <!-- MCP 客户端 SDK。刻意不引 spring-ai-starter-mcp-client：它锁 SDK 0.10.0（无 Streamable HTTP，
             只有已被 MCP 规范取代的 HTTP+SSE），且其自动配置是 yml 驱动，而我们的 MCP 服务器由 admin 存在
             DB 里（T4a spec 决策 2）。只用 SDK 本体 + 手写 McpToolCallback，Spring AI 版本不动。 -->
        <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp</artifactId>
            <version>${mcp-sdk.version}</version>
        </dependency>
```

- [x] **Step 2: 核对依赖树无版本冲突**

Run: `cd server && mvn -B dependency:tree -Dincludes='io.modelcontextprotocol.sdk:*,io.projectreactor:*,com.fasterxml.jackson.core:jackson-databind'`
Expected: `io.modelcontextprotocol.sdk:mcp:jar:0.12.1:compile` 出现；`reactor-core` 与 `jackson-databind` 仍由 Spring Boot BOM 统一收口（不出现两个不同版本）。若出现 `omitted for conflict`，记录下来在 Task 7 Step 7 复核。

- [x] **Step 3: 加超时配置**

修改 `server/src/main/resources/application.yml`——在 `agent:` 块之后、`usage:` 块之前插入：

```yaml
  tool:
    mcp:
      # MCP 客户端超时（CLAUDE.md：所有外部调用必须有超时且超时值外化）。
      # 仅支持远程 HTTP（streamable_http / sse），不支持 stdio（T4a spec 决策 1）。
      # 建立 TCP 连接超时。
      connect-timeout-ms: ${HIFY_TOOL_MCP_CONNECT_TIMEOUT_MS:5000}
      # 单次 JSON-RPC 请求超时（listTools / callTool）。
      request-timeout-ms: ${HIFY_TOOL_MCP_REQUEST_TIMEOUT_MS:30000}
      # initialize 握手超时。
      initialization-timeout-ms: ${HIFY_TOOL_MCP_INITIALIZATION_TIMEOUT_MS:10000}
```

- [x] **Step 4: 写 `McpProperties`**

创建 `server/src/main/java/com/hify/tool/config/McpProperties.java`：

```java
package com.hify.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** MCP 客户端超时配置（CLAUDE.md：外部调用必须有超时且外化）。见 application.yml 的 hify.tool.mcp。 */
@Component
@ConfigurationProperties(prefix = "hify.tool.mcp")
public class McpProperties {

    /** 建立 TCP 连接超时（毫秒）。 */
    private int connectTimeoutMs = 5000;
    /** 单次 JSON-RPC 请求超时（毫秒）：listTools / callTool。 */
    private int requestTimeoutMs = 30000;
    /** initialize 握手超时（毫秒）。 */
    private int initializationTimeoutMs = 10000;

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    public int getInitializationTimeoutMs() { return initializationTimeoutMs; }
    public void setInitializationTimeoutMs(int initializationTimeoutMs) {
        this.initializationTimeoutMs = initializationTimeoutMs;
    }
}
```

- [x] **Step 5: 写失败测试**

创建 `server/src/test/java/com/hify/tool/service/mcp/McpClientFactoryTest.java`：

```java
package com.hify.tool.service.mcp;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.outbound.SsrfValidator;
import com.hify.tool.config.McpProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpClientFactoryTest {

    private final McpClientFactory factory =
            new McpClientFactory(new SsrfValidator(), new McpProperties());

    @Test
    void create_rejectsNonHttpScheme() {
        assertThatThrownBy(() -> factory.create("ftp://example.com/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅支持 http/https");
    }

    @Test
    void create_rejectsUrlWithoutHost() {
        assertThatThrownBy(() -> factory.create("http:///mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺少主机名");
    }

    /** SSRF：内网地址必须被拒，且错误码是 10001（SsrfValidator 原样抛出，不被包装成 13002）。 */
    @Test
    void create_rejectsInternalAddress_with10001() {
        assertThatThrownBy(() -> factory.create("http://127.0.0.1:8080/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode().code())
                        .isEqualTo(CommonError.PARAM_INVALID.code()));
    }

    @Test
    void create_buildsClientForPublicUrl_bothTransports() {
        // 用 TEST-NET-1（192.0.2.0/24，RFC5737 文档保留段）——是公网地址不会被 SSRF 拒，
        // 但不可路由，绝不会真发出请求。create() 只造对象不连网。
        try (McpSyncClient a = factory.create("https://192.0.2.1/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of("Authorization", "Bearer t"));
             McpSyncClient b = factory.create("https://192.0.2.1/sse",
                     McpClientFactory.TRANSPORT_SSE, Map.of())) {
            assertThat(a).isNotNull();
            assertThat(b).isNotNull();
        }
    }
}
```

> **为什么鉴权头注入不在这里断言**：`customizeRequest` 的 Consumer 被封在 transport 内部，从外面拿不到。
> 头注入由 Task 3 的 `FakeMcpServer` **端到端**断言（真发一次请求、真检查收到的头）——那才是真测试。

- [x] **Step 6: 运行测试确认失败**

Run: `cd server && mvn -q -Dtest=McpClientFactoryTest test`
Expected: 编译失败——`McpClientFactory` 不存在。

- [x] **Step 7: 写 `McpClientFactory`**

创建 `server/src/main/java/com/hify/tool/service/mcp/McpClientFactory.java`：

```java
package com.hify.tool.service.mcp;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.outbound.SsrfValidator;
import com.hify.tool.config.McpProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * 造 McpSyncClient：SSRF 校验 → 选传输 → 注入鉴权头 → 禁重定向 → 双超时。
 * MCP 出站的<b>全部安全闸门收口于此</b>，禁止别处自建 MCP 客户端（deployment.md §5）。
 * 只支持远程 HTTP，不支持 stdio（T4a spec 决策 1）。调用方负责 close（try-with-resources）。
 */
@Component
public class McpClientFactory {

    public static final String TRANSPORT_STREAMABLE_HTTP = "streamable_http";
    public static final String TRANSPORT_SSE = "sse";

    private final SsrfValidator ssrfValidator;
    private final McpProperties props;

    public McpClientFactory(SsrfValidator ssrfValidator, McpProperties props) {
        this.ssrfValidator = ssrfValidator;
        this.props = props;
    }

    /** headers 须是<b>解密后的明文</b>。本方法只造对象、不连网（连网发生在 initialize()）。 */
    public McpSyncClient create(String url, String transport, Map<String, String> headers) {
        URI uri = validate(url);
        Map<String, String> h = headers == null ? Map.of() : headers;
        McpClientTransport t = TRANSPORT_SSE.equals(transport) ? sse(uri, h) : streamable(uri, h);
        return McpClient.sync(t)
                .requestTimeout(Duration.ofMillis(props.getRequestTimeoutMs()))
                .initializationTimeout(Duration.ofMillis(props.getInitializationTimeoutMs()))
                .clientInfo(new McpSchema.Implementation("hify", "1.0.0"))
                .build();
    }

    private URI validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BizException(CommonError.PARAM_INVALID, "MCP 地址非法：" + url);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new BizException(CommonError.PARAM_INVALID, "MCP 地址仅支持 http/https：" + url);
        }
        if (uri.getHost() == null) {
            throw new BizException(CommonError.PARAM_INVALID, "MCP 地址缺少主机名：" + url);
        }
        ssrfValidator.validate(uri.getHost());   // 内网/回环/元数据 → BizException(10001)，原样抛出
        return uri;
    }

    private McpClientTransport streamable(URI uri, Map<String, String> headers) {
        return HttpClientStreamableHttpTransport.builder(origin(uri))
                .endpoint(endpoint(uri))
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .customizeClient(b -> b.followRedirects(HttpClient.Redirect.NEVER))
                .customizeRequest(b -> headers.forEach(b::header))
                .build();
    }

    private McpClientTransport sse(URI uri, Map<String, String> headers) {
        return HttpClientSseClientTransport.builder(origin(uri))
                .sseEndpoint(endpoint(uri))
                .customizeClient(b -> b.followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs())))
                .customizeRequest(b -> headers.forEach(b::header))
                .build();
    }

    /**
     * 拆 origin：builder(baseUri) 只吃到 scheme://host:port。
     * <b>必须拆</b>——Builder 的 endpoint 默认 "/mcp"、sseEndpoint 默认 "/sse"（已反编译确认），
     * 整条 URL 当 baseUri 传会拼成 https://host/mcp/mcp。
     */
    private static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    /** 拆 path(+query) 作 endpoint；无 path 时给 "/"。 */
    private static String endpoint(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }
}
```

- [x] **Step 8: 运行测试确认通过**

Run: `cd server && mvn -q -Dtest=McpClientFactoryTest test`
Expected: 4 个测试通过。

- [x] **Step 9: Commit**

```bash
git add server/pom.xml server/src/main/resources/application.yml server/src/main/java/com/hify/tool/config/McpProperties.java server/src/main/java/com/hify/tool/service/mcp/McpClientFactory.java server/src/test/java/com/hify/tool/service/mcp/McpClientFactoryTest.java
git commit -m "feat(tool): MCP SDK 0.12.1 依赖 + McpClientFactory(SSRF/禁重定向/双超时/拆 origin+endpoint)"
```

---

## Task 3: 最小 MCP 桩 + `McpToolSpec` + `McpToolDiscoverer`

**Files:**
- Create: `server/src/test/java/com/hify/tool/service/mcp/FakeMcpServer.java`
- Create: `server/src/test/java/com/hify/tool/service/mcp/TestSsrf.java`
- Create: `server/src/main/java/com/hify/tool/service/mcp/McpToolSpec.java`
- Create: `server/src/main/java/com/hify/tool/service/mcp/DiscoveredMcpTools.java`
- Create: `server/src/main/java/com/hify/tool/service/mcp/McpToolDiscoverer.java`
- Modify: `server/src/main/java/com/hify/tool/service/ToolSpec.java`（`@JsonSubTypes` 加 mcp 一行）
- Modify: `server/src/main/java/com/hify/tool/constant/ToolError.java`（加 13002）
- Test: `server/src/test/java/com/hify/tool/service/mcp/McpToolDiscovererTest.java`

**Interfaces:**
- Consumes: `McpClientFactory#create(url, transport, headers)`（Task 2）。
- Produces:
  - `McpToolSpec(String url, String transport, List<AuthHeader> authHeaders, List<McpTool> tools, OffsetDateTime discoveredAt) implements ToolSpec`；内嵌 `McpToolSpec.AuthHeader(String name, String valueEnc)`、`McpToolSpec.McpTool(String toolName, String description, String inputSchema)`。
  - `DiscoveredMcpTools(List<McpToolSpec.McpTool> tools)`。
  - `McpToolDiscoverer#discover(String url, String transport, Map<String,String> plainHeaders) → DiscoveredMcpTools`（失败抛 `BizException(ToolError.MCP_CONNECT_FAILED)`）。
  - `ToolError.MCP_CONNECT_FAILED`（13002 / 400）。
  - `FakeMcpServer`（测试基建）：`url()`、`seenAuthHeaders()`、`forceStatus(int)`、`callToolReturns(String,boolean)`、`toolNames(List<String>)`、`close()`。
  - `TestSsrf.permissive()`（测试基建）→ 放行任意 host 的 `SsrfValidator`，供 Task 3/4 共用。

- [x] **Step 1: 写最小 MCP 服务器桩 + SSRF 测试 helper**

创建 `server/src/test/java/com/hify/tool/service/mcp/FakeMcpServer.java`：

```java
package com.hify.tool.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用最小 MCP 服务器桩（Streamable HTTP）：只实现 initialize / 通知 / tools/list / tools/call，
 * POST 进来、JSON 回去，不做 SSE（Streamable HTTP 允许单个 JSON 响应）。
 * 记录收到的 Authorization 头供断言鉴权头注入。
 */
final class FakeMcpServer implements AutoCloseable {

    private final HttpServer http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> seenAuthHeaders = new CopyOnWriteArrayList<>();

    private volatile int forcedStatus = 0;
    private volatile String callToolText = "OK";
    private volatile boolean callToolIsError = false;
    private volatile List<String> toolNames = List.of("search_docs");

    FakeMcpServer() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/mcp", this::handle);
        http.start();
    }

    String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp";
    }

    List<String> seenAuthHeaders() { return seenAuthHeaders; }

    /** >0 时所有请求直接返回该状态码（测失败路径）。 */
    void forceStatus(int status) { forcedStatus = status; }

    void callToolReturns(String text, boolean isError) {
        callToolText = text;
        callToolIsError = isError;
    }

    void toolNames(List<String> names) { toolNames = names; }

    private void handle(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null) {
            seenAuthHeaders.add(auth);
        }
        if (forcedStatus > 0) {
            ex.sendResponseHeaders(forcedStatus, -1);
            ex.close();
            return;
        }
        JsonNode req = mapper.readTree(ex.getRequestBody());
        JsonNode id = req.get("id");
        if (id == null || id.isNull()) {        // 通知（notifications/initialized）无需响应体
            ex.sendResponseHeaders(202, -1);
            ex.close();
            return;
        }
        String result = switch (req.path("method").asText()) {
            case "initialize" -> initializeResult(req);
            case "tools/list" -> toolsListResult();
            case "tools/call" -> callToolResult();
            default -> null;
        };
        String body = result == null
                ? "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}"
                : "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    /** 原样回显客户端请求的协议版本，避免版本协商失败。 */
    private String initializeResult(JsonNode req) {
        String pv = req.path("params").path("protocolVersion").asText("2024-11-05");
        return "{\"protocolVersion\":\"" + pv + "\","
                + "\"capabilities\":{\"tools\":{\"listChanged\":false}},"
                + "\"serverInfo\":{\"name\":\"fake-mcp\",\"version\":\"1.0.0\"}}";
    }

    private String toolsListResult() {
        List<String> items = new ArrayList<>();
        for (String n : toolNames) {
            items.add("{\"name\":\"" + n + "\",\"description\":\"desc of " + n + "\","
                    + "\"inputSchema\":{\"type\":\"object\","
                    + "\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}");
        }
        return "{\"tools\":[" + String.join(",", items) + "]}";
    }

    private String callToolResult() {
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + callToolText + "\"}],"
                + "\"isError\":" + callToolIsError + "}";
    }

    @Override
    public void close() {
        http.stop(0);
    }
}
```

创建 `server/src/test/java/com/hify/tool/service/mcp/TestSsrf.java`（Task 3/4 共用，别在两个测试里各抄一份）：

```java
package com.hify.tool.service.mcp;

import com.hify.infra.outbound.SsrfValidator;

import java.net.InetAddress;
import java.util.function.Function;

/**
 * 测试用：放行任意 host 的 SsrfValidator。
 *
 * <p>为什么需要：FakeMcpServer 跑在 127.0.0.1 = 回环地址，真 SsrfValidator 一定拒。
 * SsrfValidator 留了包私有构造器接受自定义解析函数（设计好的测试缝），但它在 com.hify.infra.outbound
 * 包下、本测试在 com.hify.tool.service.mcp 包，跨包访问不到，故走反射。
 *
 * <p>解析结果固定给 192.0.2.1（TEST-NET-1，RFC5737 文档保留段）——是公网地址不会被规则拒，
 * 而真正的连接仍由 URL 里的 127.0.0.1 发起（SsrfValidator 只做校验、不参与连接）。
 */
final class TestSsrf {

    private TestSsrf() {}

    static SsrfValidator permissive() {
        Function<String, InetAddress[]> publicResolver = host -> {
            try {
                return new InetAddress[]{ InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 1}) };
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        try {
            var ctor = SsrfValidator.class.getDeclaredConstructor(Function.class);
            ctor.setAccessible(true);
            return ctor.newInstance(publicResolver);
        } catch (Exception e) {
            throw new IllegalStateException("构造放行版 SsrfValidator 失败", e);
        }
    }
}
```

- [x] **Step 2: 写失败测试**

创建 `server/src/test/java/com/hify/tool/service/mcp/McpToolDiscovererTest.java`：

```java
package com.hify.tool.service.mcp;

import com.hify.common.exception.BizException;
import com.hify.infra.outbound.SsrfValidator;
import com.hify.tool.config.McpProperties;
import com.hify.tool.constant.ToolError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolDiscovererTest {

    private McpToolDiscoverer discoverer() {
        return new McpToolDiscoverer(new McpClientFactory(TestSsrf.permissive(), new McpProperties()));
    }

    @Test
    void discover_returnsToolsWithSerializedJsonSchema() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            server.toolNames(List.of("search_docs", "fetch_page"));

            DiscoveredMcpTools found = discoverer().discover(
                    server.url(), McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of());

            assertThat(found.tools()).extracting(McpToolSpec.McpTool::toolName)
                    .containsExactly("search_docs", "fetch_page");
            assertThat(found.tools().get(0).description()).isEqualTo("desc of search_docs");
            // inputSchema 必须是 JSON Schema 字符串（SDK 给的是 JsonSchema 对象，须序列化）
            assertThat(found.tools().get(0).inputSchema())
                    .contains("\"type\":\"object\"")
                    .contains("\"properties\"")
                    .doesNotContain("\"defs\":null");     // NON_NULL：不许把空字段塞进给模型的 schema
        }
    }

    /** 鉴权头必须真的发到远端——这是 McpClientFactory 的 customizeRequest 唯一能端到端验证的地方。 */
    @Test
    void discover_injectsAuthHeaders() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            discoverer().discover(server.url(), McpClientFactory.TRANSPORT_STREAMABLE_HTTP,
                    Map.of("Authorization", "Bearer secret-token"));

            assertThat(server.seenAuthHeaders()).isNotEmpty();
            assertThat(server.seenAuthHeaders()).allMatch(h -> h.equals("Bearer secret-token"));
        }
    }

    @Test
    void discover_remoteError_throws13002() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            server.forceStatus(500);

            assertThatThrownBy(() -> discoverer().discover(
                    server.url(), McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of()))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).errorCode().code())
                            .isEqualTo(ToolError.MCP_CONNECT_FAILED.code()));
        }
    }

    /** SSRF 拒绝必须原样冒泡成 10001，不得被 catch 吞掉重包成 13002（否则 admin 看不出真实原因）。 */
    @Test
    void discover_internalAddress_propagates10001NotWrapped() {
        McpToolDiscoverer d = new McpToolDiscoverer(
                new McpClientFactory(new SsrfValidator(), new McpProperties()));

        assertThatThrownBy(() -> d.discover("http://127.0.0.1:9/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode().code())
                        .isEqualTo(com.hify.common.exception.CommonError.PARAM_INVALID.code()));
    }
}
```

- [x] **Step 3: 运行测试确认失败**

Run: `cd server && mvn -q -Dtest=McpToolDiscovererTest test`
Expected: 编译失败——`McpToolDiscoverer` / `McpToolSpec` / `DiscoveredMcpTools` / `ToolError.MCP_CONNECT_FAILED` 不存在。

- [x] **Step 4: 写 `McpToolSpec`**

创建 `server/src/main/java/com/hify/tool/service/mcp/McpToolSpec.java`：

```java
package com.hify.tool.service.mcp;

import com.hify.tool.service.ToolSpec;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * tool.spec(jsonb) 的 mcp 形状：连接配置 + 工具清单快照（T4a spec 决策 4/5）。
 * 凭据只存密文 valueEnc，任何响应 DTO 不回明文。
 */
public record McpToolSpec(
        String url,
        String transport,
        List<AuthHeader> authHeaders,
        List<McpTool> tools,
        OffsetDateTime discoveredAt) implements ToolSpec {

    public record AuthHeader(String name, String valueEnc) {}

    /**
     * 快照里的一个 MCP 工具。
     * @param toolName    MCP 远端的工具名（展开时会加注册名前缀防跨注册撞名）
     * @param description <b>给模型看的</b>说明（来自远端），不是 tool 行那个给人看的注册描述
     * @param inputSchema JSON Schema 字符串（发现时由 McpSchema.JsonSchema 序列化而来），直接进 ToolDefinition
     */
    public record McpTool(String toolName, String description, String inputSchema) {}
}
```

- [x] **Step 5: 写 `DiscoveredMcpTools`**

创建 `server/src/main/java/com/hify/tool/service/mcp/DiscoveredMcpTools.java`：

```java
package com.hify.tool.service.mcp;

import java.util.List;

/** 发现中间结果（仿 openapi 的 ParsedOpenApi）：只带工具清单，落库时再补 url/transport/密文头/时间。 */
public record DiscoveredMcpTools(List<McpToolSpec.McpTool> tools) {}
```

- [x] **Step 6: `ToolSpec` 注册 mcp 子类型**

修改 `server/src/main/java/com/hify/tool/service/ToolSpec.java`——加 import 与 `@JsonSubTypes` 一行：

```java
import com.hify.tool.service.mcp.McpToolSpec;
```

```java
@JsonSubTypes({
        @JsonSubTypes.Type(value = OpenApiToolSpec.class, name = "openapi"),
        @JsonSubTypes.Type(value = McpToolSpec.class, name = "mcp")
})
```

- [x] **Step 7: 加错误码 13002**

修改 `server/src/main/java/com/hify/tool/constant/ToolError.java`：

```java
public enum ToolError implements ErrorCode {

    SPEC_PARSE_FAILED(13001, HttpStatus.BAD_REQUEST, "OpenAPI 文档解析失败"),
    /**
     * MCP 服务器连接或工具发现失败。400 而非 503：只发生在 admin 注册/刷新/预览时，本质是 admin 填错
     * 地址或凭据（用户输入问题），不是「我们依赖的服务挂了」；与 13001 同构。工具执行期连不上按
     * ToolCallback 契约返回错误文本不抛，故不存在执行期 503 场景（T4a spec §7）。
     */
    MCP_CONNECT_FAILED(13002, HttpStatus.BAD_REQUEST, "MCP 服务器连接或工具发现失败");
```

- [x] **Step 8: 写 `McpToolDiscoverer`**

创建 `server/src/main/java/com/hify/tool/service/mcp/McpToolDiscoverer.java`：

```java
package com.hify.tool.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.tool.config.ToolSpecTypeHandler;
import com.hify.tool.constant.ToolError;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 连远端 listTools 拿工具清单（注册 / 刷新 / 预览三处共用）。慢路径，允许连网。
 * 失败一律 13002；但 SSRF/非法 url 的 10001 原样冒泡，不吞不包（T4a spec §7.1）。
 */
@Service
public class McpToolDiscoverer {

    /** 序列化 inputSchema 用：NON_NULL，避免把 "defs":null 之类塞进发给模型的 schema。 */
    private static final ObjectMapper SCHEMA_MAPPER = ToolSpecTypeHandler.specMapper();

    private final McpClientFactory factory;

    public McpToolDiscoverer(McpClientFactory factory) {
        this.factory = factory;
    }

    /** headers 须是解密后的明文。 */
    public DiscoveredMcpTools discover(String url, String transport, Map<String, String> headers) {
        // create() 里的 url 校验/SSRF 抛的是 10001，放在 try 外，避免被下面的 catch 吞掉重包成 13002
        McpSyncClient client = factory.create(url, transport, headers);
        try (client) {
            client.initialize();
            McpSchema.ListToolsResult result = client.listTools();
            return new DiscoveredMcpTools(toSnapshot(result.tools()));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ToolError.MCP_CONNECT_FAILED,
                    "MCP 服务器连接或工具发现失败：" + e.getMessage());
        }
    }

    private static List<McpToolSpec.McpTool> toSnapshot(List<McpSchema.Tool> tools) {
        List<McpToolSpec.McpTool> out = new ArrayList<>(tools.size());
        for (McpSchema.Tool t : tools) {
            out.add(new McpToolSpec.McpTool(t.name(), t.description(), schemaJson(t)));
        }
        return out;
    }

    /** SDK 给的 inputSchema 是类型化的 JsonSchema record，ToolDefinition 要字符串——此处序列化掉。 */
    private static String schemaJson(McpSchema.Tool t) {
        if (t.inputSchema() == null) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        try {
            return SCHEMA_MAPPER.writeValueAsString(t.inputSchema());
        } catch (Exception e) {
            throw new BizException(ToolError.MCP_CONNECT_FAILED,
                    "MCP 工具「" + t.name() + "」的 inputSchema 无法序列化：" + e.getMessage());
        }
    }
}
```

- [x] **Step 9: 运行测试确认通过**

Run: `cd server && mvn -q -Dtest=McpToolDiscovererTest test`
Expected: 4 个测试通过。

> 若 `discover_returnsToolsWithSerializedJsonSchema` 因协议版本协商失败：检查 `FakeMcpServer.initializeResult` 是否原样回显了客户端请求的 `protocolVersion`。

- [x] **Step 10: Commit**

```bash
git add server/src/main/java/com/hify/tool server/src/test/java/com/hify/tool/service/mcp
git commit -m "feat(tool): McpToolDiscoverer(listTools 快照+schema 序列化)+McpToolSpec+13002+最小 MCP 测试桩"
```

---

## Task 4: `McpToolCallback`

**Files:**
- Create: `server/src/main/java/com/hify/tool/service/mcp/McpToolCallback.java`
- Test: `server/src/test/java/com/hify/tool/service/mcp/McpToolCallbackTest.java`

**Interfaces:**
- Consumes: `McpClientFactory#create(...)`（Task 2）、`McpToolSpec.McpTool`（Task 3）、`FakeMcpServer`（Task 3）。
- Produces: `McpToolCallback(ToolDefinition definition, String toolName, String url, String transport, Map<String,String> authHeaders, McpClientFactory factory, ObjectMapper mapper) implements ToolCallback`。

- [x] **Step 1: 写失败测试**

创建 `server/src/test/java/com/hify/tool/service/mcp/McpToolCallbackTest.java`：

```java
package com.hify.tool.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.tool.config.McpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolCallbackTest {

    private McpToolCallback callback(FakeMcpServer server) {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name("wiki__search_docs").description("查文档")
                .inputSchema("{\"type\":\"object\"}").build();
        return new McpToolCallback(def, "search_docs", server.url(),
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of(),
                new McpClientFactory(TestSsrf.permissive(), new McpProperties()), new ObjectMapper());
    }

    @Test
    void call_returnsRemoteText() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            server.callToolReturns("检索到 3 条结果", false);

            String out = callback(server).call("{\"q\":\"hify\"}");

            assertThat(out).isEqualTo("检索到 3 条结果");
        }
    }

    /** 远端标记 isError 时要点明，让模型知道调用失败可换思路，而不是把错误当正常结果。 */
    @Test
    void call_remoteIsError_prefixesMarker() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            server.callToolReturns("rate limited", true);

            String out = callback(server).call("{}");

            assertThat(out).startsWith("错误：").contains("rate limited");
        }
    }

    /** 契约：任何失败返回错误文本、绝不抛——不能中断 Agent 循环。 */
    @Test
    void call_remoteFailure_returnsTextNotThrow() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            server.forceStatus(500);

            String out = callback(server).call("{}");

            assertThat(out).startsWith("错误：");
        }
    }

    @Test
    void call_invalidArgsJson_returnsTextNotThrow() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            String out = callback(server).call("not-json");

            assertThat(out).startsWith("错误：").contains("JSON");
        }
    }

    @Test
    void call_blankArgs_treatedAsEmptyObject() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            server.callToolReturns("ok", false);

            assertThat(callback(server).call("")).isEqualTo("ok");
        }
    }
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `cd server && mvn -q -Dtest=McpToolCallbackTest test`
Expected: 编译失败——`McpToolCallback` 不存在。

- [x] **Step 3: 写 `McpToolCallback`**

创建 `server/src/main/java/com/hify/tool/service/mcp/McpToolCallback.java`：

```java
package com.hify.tool.service.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 一个 MCP 远端工具适配成 Spring AI ToolCallback。
 *
 * <p>持有的是<b>连接配置 + 这一个工具的 name</b>，不是活着的连接——与 BuiltinToolCallback /
 * OpenApiToolCallback 保持一致的无状态契约，注册表可随时造/丢，无人需要负责关连接。
 * 每次 call 建连 → initialize → callTool → close（T4a spec 决策 6）。
 *
 * <p>任何失败返回「错误：…」文本、绝不抛（不中断 Agent 循环，与内置工具同契约）。
 */
public class McpToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final String toolName;
    private final String url;
    private final String transport;
    private final Map<String, String> authHeaders;
    private final McpClientFactory factory;
    private final ObjectMapper mapper;

    public McpToolCallback(ToolDefinition definition, String toolName, String url, String transport,
                           Map<String, String> authHeaders, McpClientFactory factory, ObjectMapper mapper) {
        this.definition = definition;
        this.toolName = toolName;
        this.url = url;
        this.transport = transport;
        this.authHeaders = authHeaders;
        this.factory = factory;
        this.mapper = mapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> args;
        try {
            args = toolInput == null || toolInput.isBlank()
                    ? Map.of()
                    : mapper.readValue(toolInput, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return "错误：参数不是合法 JSON：" + e.getMessage();
        }
        try (McpSyncClient client = factory.create(url, transport, authHeaders)) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, args));
            String text = textOf(result.content());
            return Boolean.TRUE.equals(result.isError()) ? "错误：MCP 工具返回失败：" + text : text;
        } catch (Exception e) {
            return "错误：MCP 工具调用失败：" + e.getMessage();
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }

    /** 取文本内容拼接；非文本（图片/嵌入资源）给占位说明——一期不做多模态。 */
    private static String textOf(List<McpSchema.Content> content) {
        if (content == null || content.isEmpty()) {
            return "（MCP 工具无返回内容）";
        }
        StringJoiner joiner = new StringJoiner("\n");
        for (McpSchema.Content c : content) {
            if (c instanceof McpSchema.TextContent t) {
                joiner.add(t.text());
            } else {
                joiner.add("（已省略非文本内容：" + c.getClass().getSimpleName() + "）");
            }
        }
        return joiner.toString();
    }
}
```

- [x] **Step 4: 运行测试确认通过**

Run: `cd server && mvn -q -Dtest=McpToolCallbackTest test`
Expected: 5 个测试通过。

- [x] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/tool/service/mcp/McpToolCallback.java server/src/test/java/com/hify/tool/service/mcp/McpToolCallbackTest.java
git commit -m "feat(tool): McpToolCallback(每次调用建/关连接;失败返文本不抛;非文本内容占位)"
```

---

## Task 5: `ToolRegistry` 的 mcp 展开分支

**Files:**
- Modify: `server/src/main/java/com/hify/tool/service/ToolRegistry.java`
- Modify: `server/src/test/java/com/hify/tool/service/ToolRegistryOpenApiTest.java`（构造器参数增加）
- Modify: `server/src/test/java/com/hify/tool/service/ToolRegistryTest.java`（构造器参数增加）
- Modify: `server/src/test/java/com/hify/tool/service/ToolFacadeImplTest.java`（构造器参数增加）
- Test: `server/src/test/java/com/hify/tool/service/ToolRegistryMcpTest.java`

> `new ToolRegistry(` 的构造点共 **4 处**（3 处既有测试 + 本 Task 新建的 `ToolRegistryMcpTest`），
> 已全量 `grep -rn "new ToolRegistry(" server/src` 核实，Step 4 逐个补齐。

**Interfaces:**
- Consumes: `McpToolCallback`（Task 4）、`McpClientFactory`（Task 2）、`McpToolSpec`（Task 3）。
- Produces: `ToolRegistry` 构造器新增末位参数 `McpClientFactory mcpClientFactory`，完整签名变为
  `ToolRegistry(ToolMapper, List<BuiltinTool>, SecretCipher, OutboundHttpClient, ObjectMapper, McpClientFactory)`。

- [x] **Step 1: 写失败测试**

创建 `server/src/test/java/com/hify/tool/service/ToolRegistryMcpTest.java`：

```java
package com.hify.tool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.infra.crypto.SecretCipher;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.infra.outbound.SsrfValidator;
import com.hify.tool.config.McpProperties;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.builtin.BuiltinTool;
import com.hify.tool.service.mcp.McpClientFactory;
import com.hify.tool.service.mcp.McpToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryMcpTest {

    private ToolRegistry registry(ToolMapper mapper, SecretCipher cipher) {
        return new ToolRegistry(mapper, List.<BuiltinTool>of(), cipher,
                mock(OutboundHttpClient.class), new ObjectMapper(),
                new McpClientFactory(new SsrfValidator(), new McpProperties()));
    }

    private Tool mcpRow() {
        Tool row = new Tool();
        row.setId(7L);
        row.setName("wiki");
        row.setDescription("给人看的注册说明");
        row.setSource("mcp");
        row.setEnabled(true);
        row.setSpec(new McpToolSpec(
                "https://mcp.example.com/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP,
                List.of(new McpToolSpec.AuthHeader("Authorization", "ENC")),
                List.of(new McpToolSpec.McpTool("search_docs", "给模型看的说明", "{\"type\":\"object\"}"),
                        new McpToolSpec.McpTool("fetch_page", "抓页面", "{\"type\":\"object\"}")),
                OffsetDateTime.now()));
        return row;
    }

    @Test
    void mcpRow_expandsToOneCallbackPerTool_withRegistrationNamePrefix() {
        ToolMapper mapper = mock(ToolMapper.class);
        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.decrypt("ENC")).thenReturn("Bearer secret");
        when(mapper.selectList(any())).thenReturn(List.of(mcpRow()));

        List<ToolCallback> callbacks = registry(mapper, cipher).getToolCallbacks(List.of(7L));

        assertThat(callbacks).hasSize(2);
        assertThat(callbacks).extracting(c -> c.getToolDefinition().name())
                .containsExactlyInAnyOrder("wiki__search_docs", "wiki__fetch_page");
    }

    /** 给模型看的 description 必须取远端那个，不是 tool 行的注册说明。 */
    @Test
    void toolDefinition_usesRemoteDescription_notRowDescription() {
        ToolMapper mapper = mock(ToolMapper.class);
        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.decrypt("ENC")).thenReturn("Bearer secret");
        when(mapper.selectList(any())).thenReturn(List.of(mcpRow()));

        List<ToolCallback> callbacks = registry(mapper, cipher).getToolCallbacks(List.of(7L));

        assertThat(callbacks).extracting(c -> c.getToolDefinition().description())
                .contains("给模型看的说明")
                .doesNotContain("给人看的注册说明");
    }

    @Test
    void mcpRow_withNullSpecTools_isSkipped() {
        ToolMapper mapper = mock(ToolMapper.class);
        Tool row = mcpRow();
        row.setSpec(new McpToolSpec("https://mcp.example.com/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, List.of(), null, OffsetDateTime.now()));
        when(mapper.selectList(any())).thenReturn(List.of(row));

        assertThat(registry(mapper, mock(SecretCipher.class)).getToolCallbacks(List.of(7L))).isEmpty();
    }
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `cd server && mvn -q -Dtest=ToolRegistryMcpTest test`
Expected: 编译失败——`ToolRegistry` 构造器还没有 `McpClientFactory` 参数。

- [x] **Step 3: 改 `ToolRegistry`**

修改 `server/src/main/java/com/hify/tool/service/ToolRegistry.java`。

加 import：

```java
import com.hify.tool.service.mcp.McpClientFactory;
import com.hify.tool.service.mcp.McpToolCallback;
import com.hify.tool.service.mcp.McpToolSpec;
```

字段与构造器：

```java
    private final McpClientFactory mcpClientFactory;

    public ToolRegistry(ToolMapper toolMapper, List<BuiltinTool> builtinTools,
                        SecretCipher secretCipher, OutboundHttpClient outboundHttpClient,
                        ObjectMapper objectMapper, McpClientFactory mcpClientFactory) {
        this.toolMapper = toolMapper;
        this.builtinByName = builtinTools.stream()
                .collect(Collectors.toMap(BuiltinTool::name, Function.identity()));
        this.secretCipher = secretCipher;
        this.outboundHttpClient = outboundHttpClient;
        this.objectMapper = objectMapper;
        this.mcpClientFactory = mcpClientFactory;
    }
```

`buildCallbacks` 加 mcp 分支（放在 openapi 分支之后、builtin 兜底之前）：

```java
    private List<ToolCallback> buildCallbacks(List<Tool> rows) {
        List<ToolCallback> callbacks = new ArrayList<>(rows.size());
        for (Tool row : rows) {
            if ("openapi".equals(row.getSource())) {
                callbacks.addAll(expandOpenApi(row));
                continue;
            }
            if ("mcp".equals(row.getSource())) {
                callbacks.addAll(expandMcp(row));
                continue;
            }
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
```

新增 `expandMcp`（与 `expandOpenApi` 同构；放在 `expandOpenApi` 之后）：

```java
    /** mcp 行按 spec.tools[] 快照展开——热路径不连网（T4a spec 决策 5）。 */
    private List<ToolCallback> expandMcp(Tool row) {
        if (!(row.getSpec() instanceof McpToolSpec spec) || spec.tools() == null) {
            log.warn("mcp 工具行 spec 为空，跳过 id={}", row.getId());
            return List.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (spec.authHeaders() != null) {
            for (McpToolSpec.AuthHeader h : spec.authHeaders()) {
                headers.put(h.name(), secretCipher.decrypt(h.valueEnc()));
            }
        }
        String prefix = sanitizeName(row.getName());
        List<ToolCallback> out = new ArrayList<>(spec.tools().size());
        for (McpToolSpec.McpTool t : spec.tools()) {
            // description 取远端那个（给模型看），不是 row.getDescription()（给人看的注册说明）
            ToolDefinition def = DefaultToolDefinition.builder()
                    .name(prefix + "__" + t.toolName())
                    .description(t.description())
                    .inputSchema(t.inputSchema())
                    .build();
            out.add(new McpToolCallback(def, t.toolName(), spec.url(), spec.transport(),
                    headers, mcpClientFactory, objectMapper));
        }
        return out;
    }
```

- [x] **Step 4: 补齐既有测试的 `ToolRegistry` 构造器参数（3 处）**

三处都要加末位参数 `new McpClientFactory(new SsrfValidator(), new McpProperties())`，并补 import：

```java
import com.hify.infra.outbound.SsrfValidator;
import com.hify.tool.config.McpProperties;
import com.hify.tool.service.mcp.McpClientFactory;
```

**4-1** `server/src/test/java/com/hify/tool/service/ToolRegistryOpenApiTest.java`（L43 附近）：

```java
        ToolRegistry registry = new ToolRegistry(mapper, List.<BuiltinTool>of(),
                cipher, mock(OutboundHttpClient.class), new ObjectMapper(),
                new McpClientFactory(new SsrfValidator(), new McpProperties()));
```

**4-2** `server/src/test/java/com/hify/tool/service/ToolRegistryTest.java`（L26 附近的 `registry(...)` helper）：

```java
    private ToolRegistry registry(List<BuiltinTool> builtinTools) {
        return new ToolRegistry(toolMapper, builtinTools,
                mock(SecretCipher.class), mock(OutboundHttpClient.class), new ObjectMapper(),
                new McpClientFactory(new SsrfValidator(), new McpProperties()));
    }
```

**4-3** `server/src/test/java/com/hify/tool/service/ToolFacadeImplTest.java`（L24 附近的字段初始化）：

```java
    private final ToolFacadeImpl facade = new ToolFacadeImpl(new ToolRegistry(mapper, List.of(),
            mock(SecretCipher.class), mock(OutboundHttpClient.class), new ObjectMapper(),
            new McpClientFactory(new SsrfValidator(), new McpProperties())));
```

> 这三处用真的 `new SsrfValidator()` 即可——它们都不真连网（`ToolRegistry` 只造 callback 对象），
> SSRF 校验发生在 `McpClientFactory.create()` 里，本 Task 的测试不会走到那儿。

- [x] **Step 5: 运行测试确认通过**

Run: `cd server && mvn -q -Dtest='ToolRegistry*Test' test`
Expected: `ToolRegistryMcpTest` 3 个 + `ToolRegistryOpenApiTest` 原有测试全通过。

- [x] **Step 6: Commit**

```bash
git add server/src/main/java/com/hify/tool/service/ToolRegistry.java server/src/test/java/com/hify/tool/service
git commit -m "feat(tool): ToolRegistry 增 mcp 展开分支(按快照展开,热路径不连网,description 取远端)"
```

---

## Task 6: `ToolAdminService` 的 mcp 分支（create / update / refresh / delete 放开）

**Files:**
- Modify: `server/src/main/java/com/hify/tool/service/ToolAdminService.java`
- Modify: `server/src/main/java/com/hify/tool/dto/CreateToolRequest.java`
- Modify: `server/src/main/java/com/hify/tool/dto/UpdateToolRequest.java`
- Modify: `server/src/main/java/com/hify/tool/dto/PreviewToolRequest.java`
- Modify: `server/src/main/java/com/hify/tool/dto/ToolAdminDetailResponse.java`
- Modify: `server/src/main/java/com/hify/tool/dto/ToolPreviewResponse.java`
- Create: `server/src/main/java/com/hify/tool/dto/McpToolView.java`
- Modify: `server/src/main/java/com/hify/tool/controller/AdminToolController.java`（**preview 调用点**：签名与调用点是原子的，必须同 Task 改）
- Test: `server/src/test/java/com/hify/tool/service/ToolAdminServiceMcpTest.java`
- Modify: `server/src/test/java/com/hify/tool/service/ToolAdminServiceTest.java`（构造器参数增加 + DTO 参数增加 + preview 调用点）
- Modify: `server/src/test/java/com/hify/tool/controller/AdminToolControllerTest.java`（`ToolPreviewResponse` 构造加第三参）

> **本 Task 是一个编译原子单元**（同 Task 1）：`ToolAdminService.preview(String)` → `preview(PreviewToolRequest)`
> 与 `ToolPreviewResponse` / `ToolAdminDetailResponse` 加字段，会让 controller 与两个测试同时编译不过。
> **Step 4-6 之间不要跑测试**，一路改完到 Step 7 再跑绿。
>
> ⚠️ **不要为了"让 Task 6 单独编译通过"而保留 `preview(String)` 兼容重载**——那是用完就删的垃圾代码，
> 正是 W3a 那轮踩过的坑（为迁就 Task 边界造出语义危险的兼容层）。签名和调用点本就是一个原子单元。

**Interfaces:**
- Consumes: `McpToolDiscoverer#discover(...)`（Task 3）、`SecretCipher`。
- Produces:
  - `ToolAdminService` 构造器新增末位参数 `McpToolDiscoverer discoverer`：`ToolAdminService(ToolMapper, OpenApiSpecParser, SecretCipher, McpToolDiscoverer)`。
  - `ToolAdminService#refresh(Long id)`（仅 mcp 行；重新发现并覆盖快照，保留鉴权头密文）。
  - `McpToolView(String toolName, String description)`。
  - DTO 常量：`CreateToolRequest.TYPE_OPENAPI = "openapi"`、`TYPE_MCP = "mcp"`。

- [x] **Step 1: 扩展 DTO**

创建 `server/src/main/java/com/hify/tool/dto/McpToolView.java`：

```java
package com.hify.tool.dto;

/** 详情/预览里的 MCP 工具摘要（不含 inputSchema 细节，UI 够用即可；与 OperationView 同款克制）。 */
public record McpToolView(String toolName, String description) {}
```

修改 `server/src/main/java/com/hify/tool/dto/CreateToolRequest.java`：

```java
package com.hify.tool.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 注册自定义工具：openapi（粘贴文档）或 mcp（填服务器地址）+ 可选鉴权头。
 * type 缺省 = openapi——T3b 已上线的前端不传 type，不能弄坏它（T4a spec §5.2）。
 */
public record CreateToolRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 500) String description,
        String type,
        String specText,
        String url,
        String transport,
        @Valid List<AuthHeaderInput> authHeaders) {

    public static final String TYPE_OPENAPI = "openapi";
    public static final String TYPE_MCP = "mcp";

    /** 归一化：null/空 → openapi。 */
    public String typeOrDefault() {
        return type == null || type.isBlank() ? TYPE_OPENAPI : type;
    }

    @AssertTrue(message = "OpenAPI 工具须提供 specText；MCP 工具须提供 url")
    public boolean isPayloadValid() {
        return TYPE_MCP.equals(typeOrDefault())
                ? url != null && !url.isBlank()
                : specText != null && !specText.isBlank();
    }

    @AssertTrue(message = "type 只能是 openapi 或 mcp")
    public boolean isTypeValid() {
        return TYPE_OPENAPI.equals(typeOrDefault()) || TYPE_MCP.equals(typeOrDefault());
    }
}
```

修改 `server/src/main/java/com/hify/tool/dto/UpdateToolRequest.java`（同款结构，全量替换语义）：

```java
package com.hify.tool.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 全量更新：名称、描述、spec 来源（openapi 文档 / mcp 地址）、鉴权头一起替换。type 缺省 = openapi。 */
public record UpdateToolRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 500) String description,
        String type,
        String specText,
        String url,
        String transport,
        @Valid List<AuthHeaderInput> authHeaders) {

    public String typeOrDefault() {
        return type == null || type.isBlank() ? CreateToolRequest.TYPE_OPENAPI : type;
    }

    @AssertTrue(message = "OpenAPI 工具须提供 specText；MCP 工具须提供 url")
    public boolean isPayloadValid() {
        return CreateToolRequest.TYPE_MCP.equals(typeOrDefault())
                ? url != null && !url.isBlank()
                : specText != null && !specText.isBlank();
    }

    @AssertTrue(message = "type 只能是 openapi 或 mcp")
    public boolean isTypeValid() {
        return CreateToolRequest.TYPE_OPENAPI.equals(typeOrDefault())
                || CreateToolRequest.TYPE_MCP.equals(typeOrDefault());
    }
}
```

修改 `server/src/main/java/com/hify/tool/dto/PreviewToolRequest.java`：

```java
package com.hify.tool.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;

import java.util.List;

/** 预览：openapi 解析文档 / mcp 试连接并列工具，都只看不落库。type 缺省 = openapi。 */
public record PreviewToolRequest(
        String type,
        String specText,
        String url,
        String transport,
        @Valid List<AuthHeaderInput> authHeaders) {

    public String typeOrDefault() {
        return type == null || type.isBlank() ? CreateToolRequest.TYPE_OPENAPI : type;
    }

    @AssertTrue(message = "OpenAPI 预览须提供 specText；MCP 预览须提供 url")
    public boolean isPayloadValid() {
        return CreateToolRequest.TYPE_MCP.equals(typeOrDefault())
                ? url != null && !url.isBlank()
                : specText != null && !specText.isBlank();
    }
}
```

修改 `server/src/main/java/com/hify/tool/dto/ToolAdminDetailResponse.java`（只加字段）：

```java
package com.hify.tool.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * admin 详情（供 T3b/T4b 编辑表单）。authHeaderNames 只回头名，绝不回明文值。
 * openapi 行填 baseUrl/operations/rawSpec；mcp 行填 url/transport/tools/discoveredAt；另一边为 null 或 []。
 */
public record ToolAdminDetailResponse(
        Long id, String name, String description, String source, boolean enabled,
        String baseUrl, List<OperationView> operations, List<String> authHeaderNames, String rawSpec,
        String url, String transport, List<McpToolView> tools, OffsetDateTime discoveredAt) {}
```

修改 `server/src/main/java/com/hify/tool/dto/ToolPreviewResponse.java`（只加字段）：

```java
package com.hify.tool.dto;

import java.util.List;

/** 预览结果：openapi 回 baseUrl+operations；mcp 回 tools。未用到的那边为 null 或 []。 */
public record ToolPreviewResponse(String baseUrl, List<OperationView> operations, List<McpToolView> tools) {}
```

- [x] **Step 2: 写失败测试**

创建 `server/src/test/java/com/hify/tool/service/ToolAdminServiceMcpTest.java`：

```java
package com.hify.tool.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.crypto.SecretCipher;
import com.hify.infra.security.CurrentUser;
import com.hify.tool.dto.AuthHeaderInput;
import com.hify.tool.dto.CreateToolRequest;
import com.hify.tool.dto.ToolAdminDetailResponse;
import com.hify.tool.dto.ToolAdminResponse;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.mcp.DiscoveredMcpTools;
import com.hify.tool.service.mcp.McpClientFactory;
import com.hify.tool.service.mcp.McpToolDiscoverer;
import com.hify.tool.service.mcp.McpToolSpec;
import com.hify.tool.service.openapi.OpenApiSpecParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolAdminServiceMcpTest {

    private ToolMapper mapper;
    private SecretCipher cipher;
    private McpToolDiscoverer discoverer;
    private ToolAdminService service;
    private final CurrentUser admin = new CurrentUser(1L, "admin", "admin");

    @BeforeEach
    void setup() {
        mapper = mock(ToolMapper.class);
        cipher = mock(SecretCipher.class);
        discoverer = mock(McpToolDiscoverer.class);
        service = new ToolAdminService(mapper, mock(OpenApiSpecParser.class), cipher, discoverer);
    }

    private DiscoveredMcpTools discovered() {
        return new DiscoveredMcpTools(List.of(
                new McpToolSpec.McpTool("search_docs", "查文档", "{\"type\":\"object\"}")));
    }

    private Tool mcpRow() {
        Tool row = new Tool();
        row.setId(7L);
        row.setName("wiki");
        row.setDescription("维基");
        row.setSource("mcp");
        row.setEnabled(true);
        row.setOwnerId(1L);
        row.setSpec(new McpToolSpec("https://mcp.example.com/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP,
                List.of(new McpToolSpec.AuthHeader("Authorization", "OLDENC")),
                List.of(new McpToolSpec.McpTool("search_docs", "查文档", "{}")),
                OffsetDateTime.now().minusDays(1)));
        return row;
    }

    @Test
    void create_mcp_discoversEncryptsAndInserts() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(discoverer.discover(eq("https://mcp.example.com/mcp"), eq("streamable_http"), any()))
                .thenReturn(discovered());
        when(cipher.encrypt("Bearer t")).thenReturn("ENC");

        CreateToolRequest req = new CreateToolRequest("wiki", "维基", "mcp", null,
                "https://mcp.example.com/mcp", "streamable_http",
                List.of(new AuthHeaderInput("Authorization", "Bearer t")));
        ToolAdminResponse resp = service.create(req, admin);

        ArgumentCaptor<Tool> saved = ArgumentCaptor.forClass(Tool.class);
        verify(mapper).insert(saved.capture());
        Tool row = saved.getValue();
        assertThat(row.getSource()).isEqualTo("mcp");
        assertThat(row.getOwnerId()).isEqualTo(1L);
        McpToolSpec spec = (McpToolSpec) row.getSpec();
        assertThat(spec.url()).isEqualTo("https://mcp.example.com/mcp");
        assertThat(spec.transport()).isEqualTo("streamable_http");
        assertThat(spec.authHeaders().get(0).valueEnc()).isEqualTo("ENC");
        assertThat(spec.tools()).extracting(McpToolSpec.McpTool::toolName).containsExactly("search_docs");
        assertThat(spec.discoveredAt()).isNotNull();
        assertThat(resp.source()).isEqualTo("mcp");
        assertThat(resp.operationCount()).isEqualTo(1);   // mcp 行 operationCount = 工具数
    }

    @Test
    void create_mcp_defaultsTransportToStreamableHttp() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(discoverer.discover(any(), eq("streamable_http"), any())).thenReturn(discovered());

        service.create(new CreateToolRequest("wiki", "维基", "mcp", null,
                "https://mcp.example.com/mcp", null, List.of()), admin);

        verify(discoverer).discover(eq("https://mcp.example.com/mcp"), eq("streamable_http"), any());
    }

    /** 刷新：重新发现覆盖快照，鉴权头密文保留（admin 没重填密码，不能把凭据弄丢）。 */
    @Test
    void refresh_reDiscoversAndKeepsEncryptedHeaders() {
        when(mapper.selectById(7L)).thenReturn(mcpRow());
        when(cipher.decrypt("OLDENC")).thenReturn("Bearer old");
        when(discoverer.discover(any(), any(), any())).thenReturn(new DiscoveredMcpTools(List.of(
                new McpToolSpec.McpTool("search_docs", "查文档", "{}"),
                new McpToolSpec.McpTool("new_tool", "新工具", "{}"))));

        service.refresh(7L);

        ArgumentCaptor<Tool> saved = ArgumentCaptor.forClass(Tool.class);
        verify(mapper).updateById(saved.capture());
        McpToolSpec spec = (McpToolSpec) saved.getValue().getSpec();
        assertThat(spec.tools()).extracting(McpToolSpec.McpTool::toolName)
                .containsExactly("search_docs", "new_tool");
        assertThat(spec.authHeaders().get(0).valueEnc()).isEqualTo("OLDENC");
    }

    @Test
    void refresh_onOpenApiRow_rejected() {
        Tool row = new Tool();
        row.setId(9L);
        row.setSource("openapi");
        when(mapper.selectById(9L)).thenReturn(row);

        assertThatThrownBy(() -> service.refresh(9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("MCP");
    }

    /** T4a 起 delete 对 mcp 放开——守卫从 assertOpenApi 改成 assertNotBuiltin。 */
    @Test
    void delete_mcpRow_allowed() {
        when(mapper.selectById(7L)).thenReturn(mcpRow());

        service.delete(7L);

        verify(mapper).deleteById(7L);
    }

    @Test
    void delete_builtinRow_stillRejected() {
        Tool row = new Tool();
        row.setId(1L);
        row.setSource("builtin");
        when(mapper.selectById(1L)).thenReturn(row);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode().code())
                        .isEqualTo(CommonError.PARAM_INVALID.code()))
                .hasMessageContaining("内置工具");
    }

    @Test
    void get_mcpRow_returnsUrlTransportToolsAndNeverPlainSecret() {
        when(mapper.selectById(7L)).thenReturn(mcpRow());

        ToolAdminDetailResponse detail = service.get(7L);

        assertThat(detail.source()).isEqualTo("mcp");
        assertThat(detail.url()).isEqualTo("https://mcp.example.com/mcp");
        assertThat(detail.transport()).isEqualTo("streamable_http");
        assertThat(detail.tools()).extracting(com.hify.tool.dto.McpToolView::toolName)
                .containsExactly("search_docs");
        assertThat(detail.authHeaderNames()).containsExactly("Authorization");
        assertThat(detail.discoveredAt()).isNotNull();
        assertThat(detail.operations()).isEmpty();
        assertThat(detail.baseUrl()).isNull();
    }
}
```

- [x] **Step 3: 运行测试确认失败**

Run: `cd server && mvn -q -Dtest=ToolAdminServiceMcpTest test`
Expected: 编译失败——`ToolAdminService` 构造器无 `McpToolDiscoverer`、无 `refresh` 方法。

- [x] **Step 4: 改 `ToolAdminService`**

修改 `server/src/main/java/com/hify/tool/service/ToolAdminService.java`。

加 import：

```java
import com.hify.tool.dto.McpToolView;
import com.hify.tool.service.mcp.DiscoveredMcpTools;
import com.hify.tool.service.mcp.McpClientFactory;
import com.hify.tool.service.mcp.McpToolDiscoverer;
import com.hify.tool.service.mcp.McpToolSpec;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
```

字段与构造器：

```java
    private final McpToolDiscoverer discoverer;

    public ToolAdminService(ToolMapper toolMapper, OpenApiSpecParser parser, SecretCipher cipher,
                            McpToolDiscoverer discoverer) {
        this.toolMapper = toolMapper;
        this.parser = parser;
        this.cipher = cipher;
        this.discoverer = discoverer;
    }
```

`create` 按 type 分派：

```java
    @Transactional
    public ToolAdminResponse create(CreateToolRequest req, CurrentUser current) {
        assertNameFree(req.name(), null);
        boolean mcp = CreateToolRequest.TYPE_MCP.equals(req.typeOrDefault());
        Tool row = new Tool();
        row.setName(req.name());
        row.setDescription(req.description());
        row.setSource(mcp ? "mcp" : "openapi");
        row.setEnabled(true);
        row.setOwnerId(current.userId());
        row.setSpec(mcp
                ? buildMcpSpec(req.url(), req.transport(), req.authHeaders(), null)
                : buildSpecForCreate(req.specText(), req.authHeaders()));
        toolMapper.insert(row);
        return toResponse(row);
    }
```

`update` 按行的 source 分派（守卫换成 `assertNotBuiltin`）：

```java
    @Transactional
    public ToolAdminResponse update(Long id, UpdateToolRequest req) {
        Tool row = require(id);
        assertNotBuiltin(row, "修改");
        assertNameFree(req.name(), id);
        row.setName(req.name());
        row.setDescription(req.description());
        if ("mcp".equals(row.getSource())) {
            row.setSpec(buildMcpSpec(req.url(), req.transport(), req.authHeaders(),
                    row.getSpec() instanceof McpToolSpec old ? old : null));
        } else {
            row.setSpec(buildSpecForUpdate(req.specText(), req.authHeaders(),
                    row.getSpec() instanceof OpenApiToolSpec old ? old : null));
        }
        toolMapper.updateById(row);
        return toResponse(row);
    }
```

`delete` 守卫换成 `assertNotBuiltin`：

```java
    @Transactional
    public void delete(Long id) {
        Tool row = require(id);
        assertNotBuiltin(row, "删除");
        toolMapper.deleteById(id);
    }
```

新增 `refresh`：

```java
    /** 重新发现 MCP 工具清单覆盖快照；鉴权头密文原样保留（admin 没重填凭据，不能弄丢）。 */
    @Transactional
    public ToolAdminResponse refresh(Long id) {
        Tool row = require(id);
        if (!"mcp".equals(row.getSource())) {
            throw new BizException(CommonError.PARAM_INVALID, "只有 MCP 工具支持刷新");
        }
        McpToolSpec old = row.getSpec() instanceof McpToolSpec s ? s : null;
        if (old == null) {
            throw new BizException(CommonError.PARAM_INVALID, "MCP 工具配置为空，无法刷新");
        }
        Map<String, String> plain = decryptHeaders(old.authHeaders());
        DiscoveredMcpTools found = discoverer.discover(old.url(), old.transport(), plain);
        row.setSpec(new McpToolSpec(old.url(), old.transport(), old.authHeaders(),
                found.tools(), OffsetDateTime.now()));
        toolMapper.updateById(row);
        return toResponse(row);
    }
```

`preview` 按 type 分派——**改签名收整个 request**（controller 随之改，见 Task 7）：

```java
    public ToolPreviewResponse preview(PreviewToolRequest req) {
        if (CreateToolRequest.TYPE_MCP.equals(req.typeOrDefault())) {
            Map<String, String> plain = new LinkedHashMap<>();
            if (req.authHeaders() != null) {
                for (AuthHeaderInput h : req.authHeaders()) {
                    if (h.value() != null && !h.value().isBlank()) {
                        plain.put(h.name(), h.value());
                    }
                }
            }
            DiscoveredMcpTools found = discoverer.discover(req.url(), transportOrDefault(req.transport()), plain);
            return new ToolPreviewResponse(null, List.of(), found.tools().stream()
                    .map(t -> new McpToolView(t.toolName(), t.description()))
                    .toList());
        }
        ParsedOpenApi parsed = parser.parse(req.specText());
        List<OperationView> operations = parsed.operations() == null ? List.of() : parsed.operations().stream()
                .map(op -> new OperationView(op.opName(), op.method(), op.pathTemplate(), op.description()))
                .toList();
        return new ToolPreviewResponse(parsed.baseUrl(), operations, List.of());
    }
```

`get` 加 mcp 分支：

```java
    public ToolAdminDetailResponse get(Long id) {
        Tool row = require(id);
        boolean enabled = Boolean.TRUE.equals(row.getEnabled());
        if (row.getSpec() instanceof McpToolSpec spec) {
            List<McpToolView> tools = spec.tools() == null ? List.of() : spec.tools().stream()
                    .map(t -> new McpToolView(t.toolName(), t.description()))
                    .toList();
            return new ToolAdminDetailResponse(row.getId(), row.getName(), row.getDescription(), row.getSource(),
                    enabled, null, List.of(), authHeaderNames(spec.authHeaders(), null), null,
                    spec.url(), spec.transport(), tools, spec.discoveredAt());
        }
        if (!(row.getSpec() instanceof OpenApiToolSpec spec)) {
            return new ToolAdminDetailResponse(row.getId(), row.getName(), row.getDescription(), row.getSource(),
                    enabled, null, List.of(), List.of(), null, null, null, List.of(), null);
        }
        List<OperationView> operations = spec.operations() == null ? List.of() : spec.operations().stream()
                .map(op -> new OperationView(op.opName(), op.method(), op.pathTemplate(), op.description()))
                .toList();
        return new ToolAdminDetailResponse(row.getId(), row.getName(), row.getDescription(), row.getSource(),
                enabled, spec.baseUrl(), operations, authHeaderNames(null, spec.authHeaders()), spec.rawSpec(),
                null, null, List.of(), null);
    }
```

新增私有方法（放在既有私有方法区）：

```java
    private static String transportOrDefault(String transport) {
        return transport == null || transport.isBlank() ? McpClientFactory.TRANSPORT_STREAMABLE_HTTP : transport;
    }

    /** create/update 共用：发现工具 + 加密鉴权头（留空=保留旧密文，与 openapi 侧同款语义）。 */
    private McpToolSpec buildMcpSpec(String url, String transport, List<AuthHeaderInput> headers, McpToolSpec old) {
        String tp = transportOrDefault(transport);
        Map<String, String> oldEncByName = new HashMap<>();
        if (old != null && old.authHeaders() != null) {
            for (McpToolSpec.AuthHeader h : old.authHeaders()) {
                oldEncByName.put(h.name(), h.valueEnc());
            }
        }
        List<McpToolSpec.AuthHeader> encHeaders = new ArrayList<>();
        Map<String, String> plain = new LinkedHashMap<>();
        if (headers != null) {
            for (AuthHeaderInput h : headers) {
                String enc;
                if (h.value() == null || h.value().isBlank()) {
                    enc = oldEncByName.get(h.name());
                    if (enc == null) {
                        throw new BizException(CommonError.PARAM_INVALID, "鉴权头「" + h.name() + "」的值不能为空");
                    }
                    plain.put(h.name(), cipher.decrypt(enc));
                } else {
                    enc = cipher.encrypt(h.value());
                    plain.put(h.name(), h.value());
                }
                encHeaders.add(new McpToolSpec.AuthHeader(h.name(), enc));
            }
        }
        DiscoveredMcpTools found = discoverer.discover(url, tp, plain);
        return new McpToolSpec(url, tp, encHeaders, found.tools(), OffsetDateTime.now());
    }

    private Map<String, String> decryptHeaders(List<McpToolSpec.AuthHeader> headers) {
        Map<String, String> plain = new LinkedHashMap<>();
        if (headers != null) {
            for (McpToolSpec.AuthHeader h : headers) {
                plain.put(h.name(), cipher.decrypt(h.valueEnc()));
            }
        }
        return plain;
    }

    private static List<String> authHeaderNames(List<McpToolSpec.AuthHeader> mcp,
                                                List<OpenApiToolSpec.AuthHeader> openApi) {
        if (mcp != null) {
            return mcp.stream().map(McpToolSpec.AuthHeader::name).toList();
        }
        return openApi == null ? List.of() : openApi.stream().map(OpenApiToolSpec.AuthHeader::name).toList();
    }

    private void assertNotBuiltin(Tool row, String action) {
        if ("builtin".equals(row.getSource())) {
            throw new BizException(CommonError.PARAM_INVALID, "内置工具不可" + action);
        }
    }
```

删除旧的 `assertOpenApi` 方法（已被 `assertNotBuiltin` 取代）。

`toResponse` 的 `operationCount` 兼容 mcp（工具数）：

```java
    private ToolAdminResponse toResponse(Tool row) {
        Integer count = null;
        if (row.getSpec() instanceof OpenApiToolSpec s && s.operations() != null) {
            count = s.operations().size();
        } else if (row.getSpec() instanceof McpToolSpec m && m.tools() != null) {
            count = m.tools().size();     // 字段名保留（改名会弄坏 T3b 前端），mcp 行含义 = 工具数
        }
        return new ToolAdminResponse(row.getId(), row.getName(), row.getDescription(), row.getSource(),
                Boolean.TRUE.equals(row.getEnabled()), count, row.getOwnerId(), row.getCreateTime(), row.getUpdateTime());
    }
```

- [x] **Step 5: 同步 `preview` 签名的两个调用点（controller + controller 测试）**

`ToolAdminService.preview(String)` → `preview(PreviewToolRequest)` 后，下面两处会编译不过，必须同 Task 改。

**5-1** `server/src/main/java/com/hify/tool/controller/AdminToolController.java`（L44-46）——传整个 request：

```java
    @PostMapping("/preview")
    public Result<ToolPreviewResponse> preview(@Valid @RequestBody PreviewToolRequest request) {
        return Result.ok(toolAdminService.preview(request));
    }
```

**5-2** `server/src/test/java/com/hify/tool/controller/AdminToolControllerTest.java`（`预览_admin_200且返回操作`
测试里）——`ToolPreviewResponse` 现在是 3 个参数，补上 `List.of()`：

```java
        when(toolAdminService.preview(any())).thenReturn(
                new com.hify.tool.dto.ToolPreviewResponse("https://api.example.com",
                        java.util.List.of(new com.hify.tool.dto.OperationView("getPet", "GET", "/pets/{id}", "查")),
                        java.util.List.of()));
```

> 该测试的其余部分（发的 JSON body 仍只带 `specText`、不带 `type`）**刻意不改**——它正好验证了
> 「T3b 老前端不传 type 仍按 openapi 走」这条兼容契约还活着。

- [x] **Step 6: 补齐 `ToolAdminServiceTest` 的构造器与 DTO 参数**

修改 `server/src/test/java/com/hify/tool/service/ToolAdminServiceTest.java`：

`setup()` 的构造：

```java
        service = new ToolAdminService(mapper, parser, cipher, mock(McpToolDiscoverer.class));
```

`CreateToolRequest` 共 **3 处**（L57、L76、L168），补上新参数（openapi 路径 `type` 传 null）：

```java
        CreateToolRequest req = new CreateToolRequest("petstore", "宠物", null, "SPEC", null, null,
                List.of(new AuthHeaderInput("X-API-Key", "k")));
```

```java
        CreateToolRequest req = new CreateToolRequest("petstore", "宠物", null, "SPEC", null, null, List.of());
```

`UpdateToolRequest` 共 **3 处**（L126、L138、L156），注意代码里用的是**全限定名**：

```java
                new com.hify.tool.dto.UpdateToolRequest("http_request", "x", null, "SPEC", null, null, List.of())
```

```java
        service.update(9L, new com.hify.tool.dto.UpdateToolRequest("petstore2", "改名", null, "SPEC", null, null,
                List.of(new AuthHeaderInput("X-API-Key", ""))));
```

```java
        service.update(9L, new com.hify.tool.dto.UpdateToolRequest("petstore", "x", null, "SPEC", null, null,
                List.of(new AuthHeaderInput("X-API-Key", "newk"))));
```

`preview` 调用共 **2 处**（L104、L113），改传 request：

```java
        com.hify.tool.dto.ToolPreviewResponse resp = service.preview(
                new com.hify.tool.dto.PreviewToolRequest(null, "SPEC", null, null, List.of()));
```

```java
        assertThatThrownBy(() -> service.preview(
                new com.hify.tool.dto.PreviewToolRequest(null, "BAD", null, null, List.of())))
```

> **grep 时别只搜 `new Xxx(`**——本仓库多处用全限定名 `new com.hify.tool.dto.UpdateToolRequest(`，
> 带 `new ` 前缀的 grep 会**静默漏掉**它们（写计划时就这么漏过）。用类名本身搜：
> ```bash
> grep -rn "CreateToolRequest(\|UpdateToolRequest(\|PreviewToolRequest(\|ToolPreviewResponse(\|ToolAdminDetailResponse(\|ToolAdminService(" server/src
> ```

- [x] **Step 7: 运行测试确认转绿（到这里编译才重新成立）**

Run: `cd server && mvn -q -Dtest='ToolAdminService*Test,AdminToolControllerTest' test`
Expected: `ToolAdminServiceMcpTest` 8 个 + `ToolAdminServiceTest` 原有 + `AdminToolControllerTest` 原有全通过。

> 若仍报 `cannot find symbol` / 参数个数不符，说明 Step 5-6 有遗漏：用上面那条**不带 `new ` 前缀**的
> grep 命令重新找一遍。

- [x] **Step 8: Commit**

```bash
git add server/src/main/java/com/hify/tool server/src/test/java/com/hify/tool
git commit -m "feat(tool): ToolAdminService mcp 分支(create/update/refresh/preview)+delete 对 mcp 放开(assertNotBuiltin)"
```

---

## Task 7: `AdminToolController` refresh 端点 + 全量回归 + 架构文档同步（**Codex 的最后一个 Task**）

> **回归与文档刻意并入本 Task 的收尾 steps，不设独立收尾 Task**——历史教训（memory
> `workflow-canvas-c3-reviewed`）：Codex **从不跳过 Task 内的 step，但会整个跳过最后的独立 Task**
> （C2 已口头叮嘱过仍被跳）。Step 6-9 必须真跑真改，不许只勾。

**Files:**
- Modify: `server/src/main/java/com/hify/tool/controller/AdminToolController.java`
- Modify: `server/src/test/java/com/hify/tool/controller/AdminToolControllerTest.java`（refresh 端点测试）
- Modify: `docs/architecture/data-model.md`
- Modify: `docs/architecture/api-standards.md`
- Modify: `docs/architecture/deployment.md`
- Modify: `docs/architecture/er-diagram.dot`（删 `mcp_server` 节点与 `mcp_server -> tool` 边）
- Modify: `docs/architecture/er-diagram.svg`（由 .dot 重生成，勿手改）

**Interfaces:**
- Consumes: `ToolAdminService#refresh(Long)`（Task 6）。
- Produces: `POST /api/v1/admin/tool/tools/{id}/refresh` → `Result<ToolAdminResponse>`。

> `preview` 的签名与调用点已在 **Task 6 Step 5** 一并改完（签名与调用点是编译原子单元），本 Task 不再动它。

- [x] **Step 1: 写 refresh 端点的失败测试**

在 `server/src/test/java/com/hify/tool/controller/AdminToolControllerTest.java` 里加（`sample()` helper 已存在）：

```java
    @Test
    void 刷新_admin_200且返回工具() throws Exception {
        when(toolAdminService.refresh(9L)).thenReturn(sample());

        mockMvc.perform(post("/api/v1/admin/tool/tools/9/refresh")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("9"));
    }

    @Test
    void 刷新_无令牌_401且10002() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tool/tools/9/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
    }
```

- [x] **Step 2: 运行测试确认失败**

Run: `cd server && mvn -q -Dtest=AdminToolControllerTest test`
Expected: 失败——`/9/refresh` 无对应端点，返回 404 而非 200。

- [x] **Step 3: 加 refresh 端点**

修改 `server/src/main/java/com/hify/tool/controller/AdminToolController.java`，在 `disable` 方法之后加：

```java
    /** 重新发现 MCP 工具清单（仅 mcp 行）。动作子资源 POST——一期不用 PATCH。 */
    @PostMapping("/{id}/refresh")
    public Result<ToolAdminResponse> refresh(@PathVariable Long id) {
        return Result.ok(toolAdminService.refresh(id));
    }
```

- [x] **Step 4: 运行测试确认通过**

Run: `cd server && mvn -q -Dtest='Tool*Test,Mcp*Test,OpenApi*Test,AdminToolControllerTest' test`
Expected: 全绿，含新增的两个 refresh 端点测试。

- [x] **Step 5: 起服务做接口冒烟（openapi 老路径不能坏）**

Run:
```bash
cd server && mvn -q -DskipTests package && java -jar target/*.jar &
```
等启动完成后（日志出现 `Started HifyApplication`），用 admin 账号取 token 后：

```bash
# 1) 列表仍正常（T3a/T3b 老数据）
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/admin/tool/tools | head -c 500

# 2) mcp 预览：内网地址必须被拒且是 10001（不是 13002）
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"mcp","url":"http://127.0.0.1:9/mcp"}' \
  http://localhost:8080/api/v1/admin/tool/tools/preview
```
Expected: 第 1 条返回既有工具列表（含 builtin/openapi 行，`operationCount` 正常）；第 2 条返回 `"code":10001` 且 message 提到内网/保留地址。

> **验收前必须重启服务**（memory `workflow-w2-merged`：改完代码不重打包换进程，验的是旧进程）。

- [x] **Step 6: 全量回归（本 Task 的收尾，必须真跑，不许只勾）**

Run: `cd server && mvn clean test`
Expected: 全部测试通过，含 `ModularityTests`（tool 模块依赖白名单未变）与 ArchUnit `LayerRules`（DTO 不 import entity）。

> 此处**不加 `-q`**，完整输出便于确认；判定看末尾 `Tests run:` 汇总与 BUILD 结果，
> **不要 grep "BUILD SUCCESS"**（memory `mvn-quiet-verify-pitfall`）。

- [x] **Step 7: 复核依赖树无冲突**

Run: `cd server && mvn -B dependency:tree -Dincludes='io.modelcontextprotocol.sdk:*,io.projectreactor:*,com.fasterxml.jackson.core:*,com.networknt:*'`
Expected: `io.modelcontextprotocol.sdk:mcp:0.12.1` 在列；reactor/jackson 版本由 Spring Boot BOM 统一收口，无因冲突导致的降级。

- [x] **Step 8: 同步架构文档（拍板结论必须补进文档——CLAUDE.md 规矩）**

改 `docs/architecture/data-model.md`：
- 删除表清单里这一行：`| | mcp_server | MCP 服务连接配置，仅 source=mcp 的工具关联（T4 落地；T1 暂不建表） |`
- 删除关系描述里这一行：`mcp_server 1──N tool（仅 MCP 来源；内置/OpenAPI 工具此列为空）`
- 在 `tool` 表那行的说明末尾补：`spec` 经 `kind` 承载两种形状——openapi 存 baseUrl/operations/rawSpec，
  mcp 存 url/transport/工具快照/discoveredAt；**MCP 走 Model D（1 服务器 = 1 行，读时展开成 N 个工具），
  刻意不建 `mcp_server` 表**（T4a spec 决策 4）。
- 若 `docs/architecture/er-diagram.dot` 里含 `mcp_server` 节点，删掉并重生成：
  `npx -y @hpcc-js/wasm-graphviz-cli -T svg docs/architecture/er-diagram.dot > docs/architecture/er-diagram.svg`

改 `docs/architecture/api-standards.md`：§5.2 的 tool 段（13xxx）如维护了码清单，补
`13002/400 MCP 服务器连接或工具发现失败`。

改 `docs/architecture/deployment.md` §5：在 SSRF 那条的 MCP 相关表述后补一句——MCP 仅支持远程 HTTP
（`streamable_http`/`sse`），**不支持 stdio**（不在 server 容器内 spawn 子进程），出站过
`McpClientFactory` 的 SSRF 校验 + 禁重定向。

- [x] **Step 9: Commit**

```bash
git add server/src/main/java/com/hify/tool/controller/AdminToolController.java docs/architecture
git commit -m "feat(tool): admin 增 POST /tools/{id}/refresh；preview 按 type 分派；架构文档同步(废弃 mcp_server 表规划/补 13002/MCP 仅远程 HTTP)"
```

---

## Task 8: 实测真实公网 MCP 服务器（spec §9 风险验证）

> **本 Task 不由 Codex 执行**——需要跑起服务 + 真实 admin token + 出网访问第三方服务，属人工验收范畴。
> Codex 做完 Task 7 即为交付完成，**请在此停下并汇报**，不要伪造实测结果、不要为了"完成"而跳过或编造。
> 下面的内容是给人工验收用的操作手册。

**这是本轮最大的未验证风险，必须在开 T4b 之前做掉。** 决策 3（禁内网）意味着验收只能指向公网可达的 MCP 服务器。

**Files:** 无代码改动（除非实测暴露 bug）。

- [ ] **Step 1: 起服务并用 admin token 试连若干候选**

对每个候选依次试 `streamable_http`，失败再试 `sse`：

```bash
for URL in "https://mcp.deepwiki.com/mcp" "https://mcp.context7.com/mcp"; do
  echo "=== $URL (streamable_http)"
  curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"type\":\"mcp\",\"url\":\"$URL\",\"transport\":\"streamable_http\"}" \
    http://localhost:8080/api/v1/admin/tool/tools/preview
  echo
done
```

Expected（成功）：`"code":200` 且 `data.tools` 非空数组，每个含 `toolName` / `description`。

- [ ] **Step 2: 记录结果**

把**实际连通的那个 URL + transport** 记进本文件末尾的「实测结果」段，供人工验收与 T4b 使用。

- [ ] **Step 3: 分支判断——若全部失败**

**不要硬扛、不要为了通过而改测试**。按 spec §9：这是回头重议决策 3（内网放行）的明确信号。停下来，把失败详情（HTTP 状态、错误 message、traceId 对应的日志片段）交给用户拍板。**T4b 在此结论出来前不启动。**

- [ ] **Step 4: 若连通，跑一次真实注册 + Agent 端到端**

```bash
# 注册
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"name\":\"deepwiki\",\"description\":\"外部知识 MCP\",\"type\":\"mcp\",\"url\":\"<实测连通的 URL>\"}" \
  http://localhost:8080/api/v1/admin/tool/tools
# 刷新
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/admin/tool/tools/<id>/refresh
```

然后在前端建一个 Agent 应用、勾上这条 mcp 工具、发一句会触发该工具的话，确认 `tool_call` 轨迹里出现 `<注册名>__<工具名>` 且有真实返回。
## 实测结果（Task 8 填写）

> 待 Task 8 执行后填入：连通的 MCP 服务器 URL、transport、发现到的工具数与名字、以及失败候选的错误详情。

---

## 完成标准（DoD）

> Codex 的交付范围到 **Task 7** 为止（Task 1-7 全部 step 真跑真勾）。Task 8 与 self-check 入档是人工/终审环节。

1. `mvn clean test` 全绿（含 ModularityTests / ArchUnit）——Task 7 Step 6。
2. **存量兼容**：本地库里 T3a/T3b 注册的 openapi 工具，列表/详情/编辑/Agent 调用全部照常——`ToolSpecTypeHandlerTest.legacyJsonWithoutKind_stillDeserializesAsOpenApi` 是它的自动化守卫。
3. Task 8 实测：真实公网 MCP 服务器注册成功、`refresh` 可用、Agent 端到端调用出现在 `tool_call` 轨迹里；**或**明确结论「连不上」并已交用户重议决策 3。
4. 内网 MCP 地址被拒且错误码是 `10001`（不是 13002）。
5. conversation / workflow **一行未改**（`git diff --stat` 里不出现这两个模块）。
6. 架构文档已同步（data-model 的 `mcp_server` 规划已删）。
