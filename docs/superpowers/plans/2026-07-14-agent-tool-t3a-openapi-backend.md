# T3a OpenAPI 自定义工具（后端）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让管理员粘贴 OpenAPI 文档注册自定义工具（`source=openapi`），注册表读时展开成多个 Spring AI `ToolCallback`，对 Agent 透明。

**Architecture:** Model D——1 份 spec 存 `tool` 表 1 行（`spec jsonb` 自包含 baseUrl/operations/加密鉴权头/rawSpec），`ToolRegistry` 读时展开成 N 个 `OpenApiToolCallback`（每操作一个），执行走 `infra.outbound.OutboundHttpClient`（自带 SSRF/双超时）。凭据加密的 cipher 从 provider 提到 `infra.crypto` 共享。admin CRUD 在 `/api/v1/admin/tool/tools`。

**Tech Stack:** Spring Boot 3.5 + Spring AI 1.0.1（`ToolCallback`）+ MyBatis-Plus + swagger-parser-v3 2.1.22 + JUnit5/Mockito/AssertJ。

## Global Constraints

- admin 路由 `/api/v1/admin/tool/tools`（带模块段 `tool`），`SecurityConfig` 的 `hasRole("ADMIN")` 统一拦 `/api/v1/admin/**`，控制器类上不加注解。
- 一期不用 PATCH：启停用动作子资源 `POST .../enable`、`.../disable`；PUT 全量替换；DELETE 返 `Result<Void>`。
- Long 一律 JSON 序列化为字符串（infra 全局 Jackson 已配）；集合响应永不为 null（`[]`）；null 对象字段照常输出。
- 错误码优先复用通用段：`CommonError.PARAM_INVALID`(10001)、`NOT_FOUND`(10005)、`CONFLICT`(10006)；仅新增 `ToolError.SPEC_PARSE_FAILED`(13001/400)。
- 凭据只存密文，任何响应 DTO **不回传明文鉴权值**。
- 自定义工具出站**只经** `OutboundHttpClient`（禁自建 HTTP 客户端；SSRF 禁内网/元数据地址）。
- 控制器经 `CurrentUserHolder.current()` 取 `CurrentUser` 传给 service（service 层不读安全上下文，便于单测）。
- 不改旧 Flyway 迁移；本轮零新增迁移（复用 V23 的 `spec`/`owner_id` 列）。
- 无 Lombok，getter/setter 手写；实体继承 `com.hify.common.BaseEntity`；jsonb 字段需 `@TableName(autoResultMap=true)` + `@TableField(typeHandler=...)`。
- 主密钥默认值字符串**一字不改**（`dev-only-hify-provider-master-key-please-override`），配置前缀保留 `hify.provider.crypto`，application.yml/.env 不动。

---

## File Structure

**新建**
- `server/src/main/java/com/hify/infra/crypto/CryptoProperties.java` — 加密主密钥配置（前缀保留 `hify.provider.crypto`）
- `server/src/main/java/com/hify/infra/crypto/SecretCipher.java` — AES-256-GCM 加解密（= 原 provider ApiKeyCipher 搬迁）
- `server/src/main/java/com/hify/tool/service/openapi/OpenApiToolSpec.java` — spec jsonb 映射 record（baseUrl/authHeaders/operations/rawSpec）
- `server/src/main/java/com/hify/tool/service/openapi/ParsedOpenApi.java` — 解析中间结果 record（baseUrl/operations）
- `server/src/main/java/com/hify/tool/service/openapi/OpenApiSpecParser.java` — swagger-parser 解析器
- `server/src/main/java/com/hify/tool/service/openapi/OpenApiToolCallback.java` — 单操作 → Spring AI ToolCallback
- `server/src/main/java/com/hify/tool/config/OpenApiToolSpecTypeHandler.java` — spec jsonb ↔ record
- `server/src/main/java/com/hify/tool/constant/ToolError.java` — 13001
- `server/src/main/java/com/hify/tool/dto/{CreateToolRequest,UpdateToolRequest,AuthHeaderInput,ToolAdminResponse,ToolAdminDetailResponse,OperationView}.java`
- `server/src/main/java/com/hify/tool/service/ToolAdminService.java` — admin CRUD
- `server/src/main/java/com/hify/tool/controller/AdminToolController.java`

**修改**
- `server/pom.xml` — 加 swagger-parser-v3 依赖
- `server/src/main/java/com/hify/tool/entity/Tool.java` — 加 `spec` 字段 + `autoResultMap`
- `server/src/main/java/com/hify/tool/service/ToolRegistry.java` — openapi 展开分支 + `getToolCallbacks` 改造，注入 cipher/outbound/objectMapper
- `server/src/main/java/com/hify/provider/service/ChatClientFactory.java`、`ProviderService.java` — 注入点 `ApiKeyCipher`→`SecretCipher`
- `server/src/main/java/com/hify/provider/config/ProviderConfig.java` — 去掉 `@EnableConfigurationProperties(ProviderCryptoProperties.class)`

**删除**
- `server/src/main/java/com/hify/provider/service/ApiKeyCipher.java`
- `server/src/main/java/com/hify/provider/config/ProviderCryptoProperties.java`

**测试搬迁/新增**
- 搬 `provider/service/ApiKeyCipherTest.java` → `infra/crypto/SecretCipherTest.java`
- 改 `provider/service/{ChatClientFactoryTest,ProviderServiceTest}.java` 引用 `SecretCipher`
- 新增 openapi 解析/回调/registry/admin service 测试（见各 Task）

---

## Task 1: cipher 提到 infra 共享 + provider 迁移

先做，隔离风险：把加解密搬到 infra，provider 改用共享实现，跑通 provider 现有测试证明行为不变。

**Files:**
- Create: `server/src/main/java/com/hify/infra/crypto/CryptoProperties.java`
- Create: `server/src/main/java/com/hify/infra/crypto/SecretCipher.java`
- Create（搬迁）: `server/src/test/java/com/hify/infra/crypto/SecretCipherTest.java`
- Modify: `server/src/main/java/com/hify/provider/service/ChatClientFactory.java`、`ProviderService.java`
- Modify: `server/src/main/java/com/hify/provider/config/ProviderConfig.java`
- Modify: `server/src/test/java/com/hify/provider/service/ChatClientFactoryTest.java`、`ProviderServiceTest.java`
- Delete: `server/src/main/java/com/hify/provider/service/ApiKeyCipher.java`、`server/src/main/java/com/hify/provider/config/ProviderCryptoProperties.java`、`server/src/test/java/com/hify/provider/service/ApiKeyCipherTest.java`

**Interfaces:**
- Produces: `com.hify.infra.crypto.SecretCipher` — `String encrypt(String plaintext)` / `String decrypt(String encoded)`（@Component，供 provider 与 tool 注入）。

- [ ] **Step 1: 建 CryptoProperties**

`server/src/main/java/com/hify/infra/crypto/CryptoProperties.java`：
```java
package com.hify.infra.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对称加密主密钥配置。历史上归 provider，本轮提到 infra 供 provider 与 tool 共用；
 * 前缀保留 {@code hify.provider.crypto}（application.yml/.env 零改动，避免破坏已加密数据）。
 * 主密钥走 .env 的 HIFY_PROVIDER_MASTER_KEY，不在代码/yml 写明文。
 */
@Component
@ConfigurationProperties(prefix = "hify.provider.crypto")
public class CryptoProperties {

    /** 加密主密钥（任意非空字符串）；SecretCipher 用 SHA-256 派生为 32 字节 AES-256 密钥。 */
    private String masterKey;

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }
}
```

- [ ] **Step 2: 建 SecretCipher（搬 ApiKeyCipher 逻辑，逐字节等价）**

`server/src/main/java/com/hify/infra/crypto/SecretCipher.java`：
```java
package com.hify.infra.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 对称加解密（AES-256-GCM），全系统共用（provider API Key、tool 自定义工具鉴权凭据）。
 * 主密钥来自 {@link CryptoProperties}，用 SHA-256 派生固定 32 字节密钥。
 * 密文格式 {@code base64(IV ‖ ciphertext ‖ GCM tag)}，每次随机 12 字节 IV。
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(CryptoProperties properties) {
        this.key = deriveKey(properties.getMasterKey());
    }

    /** 明文 → {@code base64(IV ‖ 密文 ‖ tag)}。 */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /** {@code base64(IV ‖ 密文 ‖ tag)} → 明文。 */
    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            byte[] ct = new byte[all.length - IV_LENGTH];
            System.arraycopy(all, IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败", e);
        }
    }

    private static SecretKeySpec deriveKey(String masterKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(masterKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("派生 AES 密钥失败", e);
        }
    }
}
```

- [ ] **Step 3: 搬迁测试为 SecretCipherTest，删旧 ApiKeyCipherTest**

新建 `server/src/test/java/com/hify/infra/crypto/SecretCipherTest.java`（内容照原 `ApiKeyCipherTest`，把类型/包名换成 `SecretCipher`/`CryptoProperties`，`new CryptoProperties()` 后 `setMasterKey("test-master-key")` 构造 `new SecretCipher(props)`）。断言至少含：加密后≠明文、`decrypt(encrypt(x))==x`、两次加密同一明文密文不同（随机 IV）。删 `server/src/test/java/com/hify/provider/service/ApiKeyCipherTest.java`。

- [ ] **Step 4: provider 注入点改 SecretCipher**

`ChatClientFactory.java`：`import`/字段/构造参数 `ApiKeyCipher cipher` → `SecretCipher cipher`（`import com.hify.infra.crypto.SecretCipher;`），`cipher.decrypt(...)` 调用不变。
`ProviderService.java`：字段/构造参数 `ApiKeyCipher apiKeyCipher` → `SecretCipher secretCipher`，`import com.hify.infra.crypto.SecretCipher;`，两处 `apiKeyCipher.encrypt(...)` → `secretCipher.encrypt(...)`。

- [ ] **Step 5: ProviderConfig 去掉旧 properties 注册；删旧类**

`ProviderConfig.java`：删 `@EnableConfigurationProperties(ProviderCryptoProperties.class)` 注解与其 import（该配置类其余 Bean 保留）。删 `provider/service/ApiKeyCipher.java`、`provider/config/ProviderCryptoProperties.java`。

- [ ] **Step 6: provider 测试改引用**

`ChatClientFactoryTest.java`、`ProviderServiceTest.java`：凡 `new ApiKeyCipher(new ProviderCryptoProperties())` / `ApiKeyCipher` 类型 → 换 `new SecretCipher(cryptoProps)`（`CryptoProperties cryptoProps = new CryptoProperties(); cryptoProps.setMasterKey("test-master-key");`），import 相应改。

- [ ] **Step 7: 编译 + 跑 provider/infra 测试**

Run: `mvn -q -f server -Dtest='SecretCipherTest,ChatClientFactoryTest,ProviderServiceTest' test`
Expected: 全 PASS（注意 `-q` 静音下别 grep BUILD SUCCESS；看退出码与测试计数）。

- [ ] **Step 8: Commit**
```bash
git add -A server
git commit -m "refactor(infra): ApiKeyCipher 提到 infra.crypto.SecretCipher 共享(provider 迁移，行为不变)"
```

---

## Task 2: 引入 swagger-parser + OpenApiSpecParser

**Files:**
- Modify: `server/pom.xml`
- Create: `server/src/main/java/com/hify/tool/service/openapi/OpenApiToolSpec.java`
- Create: `server/src/main/java/com/hify/tool/service/openapi/ParsedOpenApi.java`
- Create: `server/src/main/java/com/hify/tool/constant/ToolError.java`
- Create: `server/src/main/java/com/hify/tool/service/openapi/OpenApiSpecParser.java`
- Test: `server/src/test/java/com/hify/tool/service/openapi/OpenApiSpecParserTest.java`

**Interfaces:**
- Produces:
  - `OpenApiToolSpec(String baseUrl, List<AuthHeader> authHeaders, List<Operation> operations, String rawSpec)`；嵌套 `AuthHeader(String name, String valueEnc)`、`Operation(String opName, String method, String pathTemplate, String description, String inputSchema, List<Param> parameters)`、`Param(String name, String in, boolean required)`。
  - `ParsedOpenApi(String baseUrl, List<OpenApiToolSpec.Operation> operations)`
  - `OpenApiSpecParser.parse(String specText) -> ParsedOpenApi`（解析失败抛 `BizException(ToolError.SPEC_PARSE_FAILED, msg)`）
  - `ToolError.SPEC_PARSE_FAILED`（13001/400）

- [ ] **Step 1: pom 加 swagger-parser 依赖**

`server/pom.xml`：`<properties>` 段加 `<swagger-parser.version>2.1.22</swagger-parser.version>`；`<dependencies>` 段加：
```xml
<dependency>
    <groupId>io.swagger.parser.v3</groupId>
    <artifactId>swagger-parser-v3</artifactId>
    <version>${swagger-parser.version}</version>
</dependency>
```
Run: `mvn -q -f server dependency:resolve -Dsilent=true` 或直接下一步编译，确认能拉到。

- [ ] **Step 2: 建 OpenApiToolSpec + ParsedOpenApi record**

`OpenApiToolSpec.java`：
```java
package com.hify.tool.service.openapi;

import java.util.List;

/** tool.spec(jsonb) 映射：一条 openapi 注册的自包含执行描述。凭据只存密文 valueEnc。 */
public record OpenApiToolSpec(
        String baseUrl,
        List<AuthHeader> authHeaders,
        List<Operation> operations,
        String rawSpec) {

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
`ParsedOpenApi.java`：
```java
package com.hify.tool.service.openapi;

import java.util.List;

/** OpenApiSpecParser 解析结果（尚不含鉴权头，鉴权由 admin 请求单独提供）。 */
public record ParsedOpenApi(String baseUrl, List<OpenApiToolSpec.Operation> operations) {}
```

- [ ] **Step 3: 建 ToolError**

`ToolError.java`：
```java
package com.hify.tool.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** tool 模块特有错误码（13xxx）。通用语义（不存在/参数错/冲突）复用 CommonError。 */
public enum ToolError implements ErrorCode {

    SPEC_PARSE_FAILED(13001, HttpStatus.BAD_REQUEST, "OpenAPI 文档解析失败");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    ToolError(int code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override public int code() { return code; }
    @Override public HttpStatus status() { return status; }
    @Override public String defaultMessage() { return defaultMessage; }
}
```

- [ ] **Step 4: 写 OpenApiSpecParserTest（失败测试）**

`server/src/test/java/com/hify/tool/service/openapi/OpenApiSpecParserTest.java`：
```java
package com.hify.tool.service.openapi;

import com.hify.common.exception.BizException;
import com.hify.tool.constant.ToolError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiSpecParserTest {

    private final OpenApiSpecParser parser = new OpenApiSpecParser();

    private static final String PETSTORE_JSON = """
        {"openapi":"3.0.0","info":{"title":"Pet","version":"1.0"},
         "servers":[{"url":"https://api.example.com/v1"}],
         "paths":{"/pets/{petId}":{"get":{"operationId":"getPetById",
           "summary":"根据 id 查宠物",
           "parameters":[{"name":"petId","in":"path","required":true,
             "schema":{"type":"integer"}}]}}}}""";

    @Test
    void parse_json_extractsBaseUrlAndOperation() {
        ParsedOpenApi parsed = parser.parse(PETSTORE_JSON);
        assertThat(parsed.baseUrl()).isEqualTo("https://api.example.com/v1");
        assertThat(parsed.operations()).hasSize(1);
        OpenApiToolSpec.Operation op = parsed.operations().get(0);
        assertThat(op.opName()).isEqualTo("getPetById");
        assertThat(op.method()).isEqualTo("GET");
        assertThat(op.pathTemplate()).isEqualTo("/pets/{petId}");
        assertThat(op.description()).contains("查宠物");
        assertThat(op.inputSchema()).contains("petId");
        assertThat(op.parameters()).extracting(OpenApiToolSpec.Param::name).contains("petId");
        assertThat(op.parameters().get(0).in()).isEqualTo("path");
        assertThat(op.parameters().get(0).required()).isTrue();
    }

    @Test
    void parse_yaml_supported() {
        String yaml = """
            openapi: 3.0.0
            info: {title: Pet, version: "1.0"}
            servers: [{url: "https://api.example.com"}]
            paths:
              /ping:
                get:
                  operationId: ping
                  summary: 健康检查
            """;
        ParsedOpenApi parsed = parser.parse(yaml);
        assertThat(parsed.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(parsed.operations()).extracting(OpenApiToolSpec.Operation::opName).contains("ping");
    }

    @Test
    void parse_resolvesRefInRequestBody() {
        String spec = """
            {"openapi":"3.0.0","info":{"title":"T","version":"1"},
             "servers":[{"url":"https://api.example.com"}],
             "paths":{"/pets":{"post":{"operationId":"addPet",
               "requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Pet"}}}}}}},
             "components":{"schemas":{"Pet":{"type":"object",
               "required":["name"],
               "properties":{"name":{"type":"string","description":"名字"}}}}}}""";
        ParsedOpenApi parsed = parser.parse(spec);
        OpenApiToolSpec.Operation op = parsed.operations().get(0);
        assertThat(op.inputSchema()).contains("name");
        assertThat(op.parameters()).extracting(OpenApiToolSpec.Param::name).contains("name");
        assertThat(op.parameters()).filteredOn(p -> p.name().equals("name"))
                .extracting(OpenApiToolSpec.Param::in).containsOnly("body");
    }

    @Test
    void parse_invalid_throws13001() {
        assertThatThrownBy(() -> parser.parse("这不是 spec"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ToolError.SPEC_PARSE_FAILED));
    }

    @Test
    void parse_noServers_throws13001() {
        String spec = """
            {"openapi":"3.0.0","info":{"title":"T","version":"1"},
             "paths":{"/x":{"get":{"operationId":"x"}}}}""";
        assertThatThrownBy(() -> parser.parse(spec)).isInstanceOf(BizException.class);
    }
}
```
> 注：`BizException` 的错误码访问器是 `errorCode()`（已核实，非 `getErrorCode`）。

- [ ] **Step 5: 运行确认失败**

Run: `mvn -q -f server -Dtest=OpenApiSpecParserTest test`
Expected: 编译失败/测试失败（`OpenApiSpecParser` 未实现）。

- [ ] **Step 6: 实现 OpenApiSpecParser**

`OpenApiSpecParser.java`：
```java
package com.hify.tool.service.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.tool.constant.ToolError;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAPI 文档解析：swagger-parser 解 $ref/YAML/JSON，抽出 baseUrl + 每操作的
 * method/path/描述/合并入参 inputSchema。失败一律抛 {@link ToolError#SPEC_PARSE_FAILED}。
 */
@Component
public class OpenApiSpecParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ParsedOpenApi parse(String specText) {
        OpenAPI openAPI = readOrThrow(specText);
        String baseUrl = baseUrlOrThrow(openAPI);
        List<OpenApiToolSpec.Operation> ops = new ArrayList<>();
        if (openAPI.getPaths() != null) {
            openAPI.getPaths().forEach((path, item) ->
                    item.readOperationsMap().forEach((httpMethod, op) ->
                            ops.add(toOperation(path, httpMethod, op))));
        }
        if (ops.isEmpty()) {
            throw new BizException(ToolError.SPEC_PARSE_FAILED, "文档里没有任何可用接口操作");
        }
        return new ParsedOpenApi(baseUrl, ops);
    }

    private OpenAPI readOrThrow(String specText) {
        try {
            ParseOptions opts = new ParseOptions();
            opts.setResolveFully(true);
            SwaggerParseResult result = new OpenAPIV3Parser().readContents(specText, null, opts);
            OpenAPI openAPI = result.getOpenAPI();
            if (openAPI == null) {
                String msg = (result.getMessages() == null || result.getMessages().isEmpty())
                        ? "无法识别为 OpenAPI 文档" : String.join("; ", result.getMessages());
                throw new BizException(ToolError.SPEC_PARSE_FAILED, msg);
            }
            return openAPI;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ToolError.SPEC_PARSE_FAILED, "解析异常：" + e.getMessage());
        }
    }

    private String baseUrlOrThrow(OpenAPI openAPI) {
        if (openAPI.getServers() == null || openAPI.getServers().isEmpty()) {
            throw new BizException(ToolError.SPEC_PARSE_FAILED, "文档缺少 servers（需绝对 baseUrl）");
        }
        String url = openAPI.getServers().get(0).getUrl();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new BizException(ToolError.SPEC_PARSE_FAILED, "servers[0].url 必须是绝对 http/https 地址");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private OpenApiToolSpec.Operation toOperation(String path, PathItem.HttpMethod httpMethod, Operation op) {
        String method = httpMethod.name();
        String opName = (op.getOperationId() != null && !op.getOperationId().isBlank())
                ? sanitize(op.getOperationId())
                : sanitize(method.toLowerCase(Locale.ROOT) + "_" + path);
        String description = firstNonBlank(op.getSummary(), op.getDescription(), opName);

        List<OpenApiToolSpec.Param> params = new ArrayList<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        if (op.getParameters() != null) {
            for (Parameter p : op.getParameters()) {
                if (p.getName() == null || p.getIn() == null) continue;
                params.add(new OpenApiToolSpec.Param(p.getName(), p.getIn(), Boolean.TRUE.equals(p.getRequired())));
                properties.put(p.getName(), schemaProp(p.getSchema(), p.getDescription()));
                if (Boolean.TRUE.equals(p.getRequired())) required.add(p.getName());
            }
        }
        RequestBody body = op.getRequestBody();
        if (body != null && body.getContent() != null && body.getContent().get("application/json") != null) {
            Schema<?> bodySchema = body.getContent().get("application/json").getSchema();
            if (bodySchema != null && bodySchema.getProperties() != null) {
                List<?> bodyRequired = bodySchema.getRequired() == null ? List.of() : bodySchema.getRequired();
                bodySchema.getProperties().forEach((k, v) -> {
                    String name = String.valueOf(k);
                    boolean req = bodyRequired.contains(name);
                    params.add(new OpenApiToolSpec.Param(name, "body", req));
                    properties.put(name, schemaProp((Schema<?>) v, ((Schema<?>) v).getDescription()));
                    if (req) required.add(name);
                });
            }
        }

        String inputSchema = writeSchema(properties, required);
        return new OpenApiToolSpec.Operation(opName, method, path, description, inputSchema, params);
    }

    private Map<String, Object> schemaProp(Schema<?> schema, String desc) {
        Map<String, Object> prop = new LinkedHashMap<>();
        String type = (schema != null && schema.getType() != null) ? schema.getType() : "string";
        prop.put("type", type);
        String d = firstNonBlank(desc, schema == null ? null : schema.getDescription(), null);
        if (d != null) prop.put("description", d);
        return prop;
    }

    private String writeSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        try {
            return MAPPER.writeValueAsString(schema);
        } catch (Exception e) {
            throw new BizException(ToolError.SPEC_PARSE_FAILED, "构造入参 schema 失败：" + e.getMessage());
        }
    }

    private static String sanitize(String raw) {
        String s = raw.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_");
        s = s.replaceAll("^_+", "").replaceAll("_+$", "");
        return s.isBlank() ? "op" : s;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn -q -f server -Dtest=OpenApiSpecParserTest test`
Expected: 5 个测试全 PASS。（若 `BizException` 访问器名不符，先修断言/或访问器用法。）

- [ ] **Step 8: Commit**
```bash
git add -A server
git commit -m "feat(tool): OpenApiSpecParser(swagger-parser)+OpenApiToolSpec+ToolError(13001)"
```

---

## Task 3: Tool 实体挂 spec jsonb + TypeHandler

**Files:**
- Create: `server/src/main/java/com/hify/tool/config/OpenApiToolSpecTypeHandler.java`
- Modify: `server/src/main/java/com/hify/tool/entity/Tool.java`
- Test: `server/src/test/java/com/hify/tool/config/OpenApiToolSpecTypeHandlerTest.java`

**Interfaces:**
- Produces: `Tool.getSpec()/setSpec(OpenApiToolSpec)`；`OpenApiToolSpecTypeHandler`（jsonb ↔ record，读空值→null）。

- [ ] **Step 1: 建 TypeHandler**

`OpenApiToolSpecTypeHandler.java`（仿 `AppConfigTypeHandler`，读空→null）：
```java
package com.hify.tool.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** tool.spec(jsonb) ↔ {@link OpenApiToolSpec}。builtin 行 spec 为 null（读空→null）。实体需 autoResultMap=true。 */
public class OpenApiToolSpecTypeHandler extends BaseTypeHandler<OpenApiToolSpec> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, OpenApiToolSpec parameter, JdbcType jdbcType)
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
    public OpenApiToolSpec getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public OpenApiToolSpec getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public OpenApiToolSpec getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private OpenApiToolSpec parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, OpenApiToolSpec.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("反序列化 tool.spec 失败", e);
        }
    }
}
```

- [ ] **Step 2: Tool 实体加 spec 字段 + autoResultMap**

`Tool.java`：`@TableName("tool")` → `@TableName(value = "tool", autoResultMap = true)`；加：
```java
import com.baomidou.mybatisplus.annotation.TableField;
import com.hify.tool.config.OpenApiToolSpecTypeHandler;
import com.hify.tool.service.openapi.OpenApiToolSpec;
```
字段：
```java
    @TableField(typeHandler = OpenApiToolSpecTypeHandler.class)
    private OpenApiToolSpec spec;

    public OpenApiToolSpec getSpec() { return spec; }
    public void setSpec(OpenApiToolSpec spec) { this.spec = spec; }
```

- [ ] **Step 3: 写 TypeHandler 单测（往返序列化，纯 Jackson，不连库）**

`OpenApiToolSpecTypeHandlerTest.java`：
```java
package com.hify.tool.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiToolSpecTypeHandlerTest {

    @Test
    void roundTrip_preservesFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OpenApiToolSpec spec = new OpenApiToolSpec(
                "https://api.example.com",
                List.of(new OpenApiToolSpec.AuthHeader("X-API-Key", "ENC")),
                List.of(new OpenApiToolSpec.Operation("getPet", "GET", "/pets/{id}", "查",
                        "{\"type\":\"object\"}",
                        List.of(new OpenApiToolSpec.Param("id", "path", true)))),
                "raw");
        String json = mapper.writeValueAsString(spec);
        OpenApiToolSpec back = mapper.readValue(json, OpenApiToolSpec.class);
        assertThat(back.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(back.authHeaders().get(0).valueEnc()).isEqualTo("ENC");
        assertThat(back.operations().get(0).parameters().get(0).in()).isEqualTo("path");
    }
}
```

- [ ] **Step 4: 运行**

Run: `mvn -q -f server -Dtest=OpenApiToolSpecTypeHandlerTest test`
Expected: PASS。

- [ ] **Step 5: Commit**
```bash
git add -A server
git commit -m "feat(tool): Tool 实体挂 spec(jsonb)+OpenApiToolSpecTypeHandler"
```

---

## Task 4: OpenApiToolCallback（单操作 → 执行）

**Files:**
- Create: `server/src/main/java/com/hify/tool/service/openapi/OpenApiToolCallback.java`
- Test: `server/src/test/java/com/hify/tool/service/openapi/OpenApiToolCallbackTest.java`

**Interfaces:**
- Consumes: `OpenApiToolSpec.Operation`、`OutboundHttpClient.send(String method, String url, Map<String,String> headers, String body) -> OutboundResponse(int status, String body, Map headers)`。
- Produces: `new OpenApiToolCallback(ToolDefinition def, OpenApiToolSpec.Operation op, String baseUrl, Map<String,String> authHeaders, OutboundHttpClient http, ObjectMapper mapper)`，实现 `ToolCallback`；`call(argsJson)` 拼请求执行、任何失败返回错误文本不抛。

- [ ] **Step 1: 写失败测试（Mockito mock OutboundHttpClient + ArgumentCaptor）**

`OpenApiToolCallbackTest.java`：
```java
package com.hify.tool.service.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.infra.outbound.OutboundResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenApiToolCallbackTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private OpenApiToolCallback callback(OpenApiToolSpec.Operation op, OutboundHttpClient http) {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name("api__" + op.opName()).description(op.description())
                .inputSchema(op.inputSchema()).build();
        return new OpenApiToolCallback(def, op, "https://api.example.com/v1",
                Map.of("X-API-Key", "secret"), http, mapper);
    }

    @Test
    void call_substitutesPath_injectsAuthHeader_appendsQuery() {
        OpenApiToolSpec.Operation op = new OpenApiToolSpec.Operation(
                "getPetById", "GET", "/pets/{petId}", "查",
                "{}", List.of(
                        new OpenApiToolSpec.Param("petId", "path", true),
                        new OpenApiToolSpec.Param("verbose", "query", false)));
        OutboundHttpClient http = mock(OutboundHttpClient.class);
        when(http.send(any(), any(), any(), any()))
                .thenReturn(new OutboundResponse(200, "{\"name\":\"Fido\"}", Map.of()));

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);

        String result = callback(op, http).call("{\"petId\":42,\"verbose\":true}");

        verify(http).send(eq("GET"), url.capture(), headers.capture(), eq(null));
        assertThat(url.getValue()).isEqualTo("https://api.example.com/v1/pets/42?verbose=true");
        assertThat(headers.getValue()).containsEntry("X-API-Key", "secret");
        assertThat(result).contains("HTTP 200").contains("Fido");
    }

    @Test
    void call_buildsJsonBody_forBodyParams() {
        OpenApiToolSpec.Operation op = new OpenApiToolSpec.Operation(
                "addPet", "POST", "/pets", "加", "{}",
                List.of(new OpenApiToolSpec.Param("name", "body", true)));
        OutboundHttpClient http = mock(OutboundHttpClient.class);
        when(http.send(any(), any(), any(), any()))
                .thenReturn(new OutboundResponse(201, "ok", Map.of()));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        callback(op, http).call("{\"name\":\"Fido\"}");

        verify(http).send(eq("POST"), eq("https://api.example.com/v1/pets"), any(), body.capture());
        assertThat(body.getValue()).contains("\"name\":\"Fido\"");
    }

    @Test
    void call_missingRequiredParam_returnsErrorText_noThrow() {
        OpenApiToolSpec.Operation op = new OpenApiToolSpec.Operation(
                "getPetById", "GET", "/pets/{petId}", "查", "{}",
                List.of(new OpenApiToolSpec.Param("petId", "path", true)));
        OutboundHttpClient http = mock(OutboundHttpClient.class);
        String result = callback(op, http).call("{}");
        assertThat(result).startsWith("错误：").contains("petId");
    }

    @Test
    void call_invalidJson_returnsErrorText() {
        OpenApiToolSpec.Operation op = new OpenApiToolSpec.Operation(
                "x", "GET", "/x", "x", "{}", List.of());
        OutboundHttpClient http = mock(OutboundHttpClient.class);
        assertThat(callback(op, http).call("not json")).startsWith("错误：");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -f server -Dtest=OpenApiToolCallbackTest test`
Expected: 编译/测试失败。

- [ ] **Step 3: 实现 OpenApiToolCallback**

`OpenApiToolCallback.java`：
```java
package com.hify.tool.service.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.common.exception.BizException;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.infra.outbound.OutboundResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条 OpenAPI 操作适配成 Spring AI ToolCallback。call(argsJson)：按 parameters 把模型参数
 * 填进 path/query/body、并入鉴权头 → 走 OutboundHttpClient（自带 SSRF/双超时）→ 返回文本。
 * 任何失败返回「错误：…」文本、绝不抛（不中断 Agent 循环，与内置工具同契约）。
 */
public class OpenApiToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final OpenApiToolSpec.Operation op;
    private final String baseUrl;
    private final Map<String, String> authHeaders;
    private final OutboundHttpClient http;
    private final ObjectMapper mapper;

    public OpenApiToolCallback(ToolDefinition definition, OpenApiToolSpec.Operation op, String baseUrl,
                               Map<String, String> authHeaders, OutboundHttpClient http, ObjectMapper mapper) {
        this.definition = definition;
        this.op = op;
        this.baseUrl = baseUrl;
        this.authHeaders = authHeaders;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        JsonNode args;
        try {
            args = mapper.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
        } catch (Exception e) {
            return "错误：参数不是合法 JSON：" + e.getMessage();
        }
        try {
            String path = op.pathTemplate();
            List<String> query = new ArrayList<>();
            Map<String, String> headers = new LinkedHashMap<>(authHeaders);
            ObjectNode body = mapper.createObjectNode();
            boolean hasBody = false;

            for (OpenApiToolSpec.Param p : op.parameters()) {
                JsonNode v = args.get(p.name());
                boolean missing = v == null || v.isNull();
                if (missing) {
                    if (p.required()) {
                        return "错误：缺少必填参数：" + p.name();
                    }
                    continue;
                }
                switch (p.in()) {
                    case "path" -> path = path.replace("{" + p.name() + "}", enc(v.asText()));
                    case "query" -> query.add(enc(p.name()) + "=" + enc(v.asText()));
                    case "header" -> headers.put(p.name(), v.asText());
                    case "body" -> { body.set(p.name(), v); hasBody = true; }
                    default -> { /* 未知 in 忽略 */ }
                }
            }

            String url = baseUrl + path + (query.isEmpty() ? "" : "?" + String.join("&", query));
            String bodyStr = hasBody ? mapper.writeValueAsString(body) : null;
            OutboundResponse resp = http.send(op.method(), url, headers, bodyStr);
            return "HTTP " + resp.status() + "\n" + resp.body();
        } catch (BizException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            return "错误：调用失败：" + e.getMessage();
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
```
> 注意 path 参数替换用 `enc(...)` 对值做 URL 编码；测试里 `petId=42` 编码后仍是 `42`，`verbose=true` 同理，断言成立。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -f server -Dtest=OpenApiToolCallbackTest test`
Expected: 4 个测试全 PASS。

- [ ] **Step 5: Commit**
```bash
git add -A server
git commit -m "feat(tool): OpenApiToolCallback(拼请求→OutboundHttpClient，失败回错误文本不抛)"
```

---

## Task 5: ToolRegistry openapi 展开分支

**Files:**
- Modify: `server/src/main/java/com/hify/tool/service/ToolRegistry.java`
- Test: `server/src/test/java/com/hify/tool/service/ToolRegistryOpenApiTest.java`

**Interfaces:**
- Consumes: `SecretCipher.decrypt`、`OutboundHttpClient`、`ObjectMapper`、`OpenApiToolSpec`。
- Produces: `getToolCallbacks(Collection<Long> ids)` 对 openapi 行展开成 N 个 `OpenApiToolCallback`（工具名 `sanitize(注册名)__opName`）；builtin 行不变。

- [ ] **Step 1: 写失败测试（mock ToolMapper 返回 openapi 行 + mock SecretCipher）**

`ToolRegistryOpenApiTest.java`：
```java
package com.hify.tool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.infra.crypto.SecretCipher;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.builtin.BuiltinTool;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryOpenApiTest {

    @Test
    void getToolCallbacks_expandsOpenApiRow_intoOnePerOperation() {
        ToolMapper mapper = mock(ToolMapper.class);
        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.decrypt("ENC")).thenReturn("secret");

        Tool row = new Tool();
        row.setId(9L);
        row.setName("petstore");
        row.setSource("openapi");
        row.setEnabled(true);
        row.setSpec(new OpenApiToolSpec(
                "https://api.example.com",
                List.of(new OpenApiToolSpec.AuthHeader("X-API-Key", "ENC")),
                List.of(
                        new OpenApiToolSpec.Operation("getPet", "GET", "/pets/{id}", "查", "{}",
                                List.of(new OpenApiToolSpec.Param("id", "path", true))),
                        new OpenApiToolSpec.Operation("addPet", "POST", "/pets", "加", "{}", List.of())),
                "raw"));
        when(mapper.selectList(any())).thenReturn(List.of(row));

        ToolRegistry registry = new ToolRegistry(mapper, List.<BuiltinTool>of(),
                cipher, mock(OutboundHttpClient.class), new ObjectMapper());

        List<ToolCallback> callbacks = registry.getToolCallbacks(List.of(9L));
        assertThat(callbacks).hasSize(2);
        assertThat(callbacks).extracting(c -> c.getToolDefinition().name())
                .containsExactlyInAnyOrder("petstore__getPet", "petstore__addPet");
    }
}
```
> 注：`ToolRegistry` 构造签名将新增 3 参（cipher/outbound/objectMapper）。既有 T1 的 `ToolRegistry` 测试（若有）需同步补参——实现前 `grep -rn "new ToolRegistry(" server/src/test` 全量核对。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -f server -Dtest=ToolRegistryOpenApiTest test`
Expected: 编译失败（构造签名/方法未改）。

- [ ] **Step 3: 改 ToolRegistry**

`ToolRegistry.java` 关键改动：
1. 构造新增依赖：
```java
import com.hify.infra.crypto.SecretCipher;
import com.hify.infra.outbound.OutboundHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.tool.service.openapi.OpenApiToolCallback;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import java.util.LinkedHashMap;
```
```java
    private final SecretCipher secretCipher;
    private final OutboundHttpClient outboundHttpClient;
    private final ObjectMapper objectMapper;

    public ToolRegistry(ToolMapper toolMapper, List<BuiltinTool> builtinTools,
                        SecretCipher secretCipher, OutboundHttpClient outboundHttpClient,
                        ObjectMapper objectMapper) {
        this.toolMapper = toolMapper;
        this.builtinByName = builtinTools.stream()
                .collect(Collectors.toMap(BuiltinTool::name, Function.identity()));
        this.secretCipher = secretCipher;
        this.outboundHttpClient = outboundHttpClient;
        this.objectMapper = objectMapper;
    }
```
2. `getToolCallbacks(ids)` 去掉 `source=builtin` 限定（查 enabled + in ids，任意 source）：
```java
    public List<ToolCallback> getToolCallbacks(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Tool> rows = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .eq(Tool::getEnabled, true)
                .in(Tool::getId, ids)
                .orderByAsc(Tool::getName));
        return buildCallbacks(rows);
    }
```
（`getBuiltinToolCallbacks()` 保留 `source=builtin` 过滤不动；`filterEnabledIds` 不动。）
3. `buildCallbacks` 按 source 分发：
```java
    private List<ToolCallback> buildCallbacks(List<Tool> rows) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool row : rows) {
            if ("openapi".equals(row.getSource())) {
                callbacks.addAll(expandOpenApi(row));
            } else {
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
        }
        return callbacks;
    }

    private List<ToolCallback> expandOpenApi(Tool row) {
        OpenApiToolSpec spec = row.getSpec();
        if (spec == null || spec.operations() == null) {
            log.warn("openapi 工具行 spec 为空，跳过 id={}", row.getId());
            return List.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (spec.authHeaders() != null) {
            for (OpenApiToolSpec.AuthHeader h : spec.authHeaders()) {
                headers.put(h.name(), secretCipher.decrypt(h.valueEnc()));
            }
        }
        String prefix = sanitizeName(row.getName());
        List<ToolCallback> out = new ArrayList<>(spec.operations().size());
        for (OpenApiToolSpec.Operation op : spec.operations()) {
            ToolDefinition def = DefaultToolDefinition.builder()
                    .name(prefix + "__" + op.opName())
                    .description(op.description())
                    .inputSchema(op.inputSchema())
                    .build();
            out.add(new OpenApiToolCallback(def, op, spec.baseUrl(), headers, outboundHttpClient, objectMapper));
        }
        return out;
    }

    private static String sanitizeName(String raw) {
        String s = raw.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_");
        return s.replaceAll("^_+", "").replaceAll("_+$", "");
    }
```

- [ ] **Step 4: 补既有 ToolRegistry 测试构造参数（已确认两个文件）**

以下两文件都用 2 参 `new ToolRegistry(...)`，每处补 3 个新参 `mock(SecretCipher.class), mock(OutboundHttpClient.class), new ObjectMapper()`：
- `server/src/test/java/com/hify/tool/service/ToolRegistryTest.java`（多处 `new ToolRegistry(toolMapper, ...)`）
- `server/src/test/java/com/hify/tool/service/ToolFacadeImplTest.java`（`new ToolFacadeImpl(new ToolRegistry(mapper, List.of()))`）
再 `grep -rn "new ToolRegistry(" server/src/test` 兜底确认无遗漏。

- [ ] **Step 5: 运行**

Run: `mvn -q -f server -Dtest='ToolRegistryOpenApiTest,ToolRegistry*Test' test`
Expected: 全 PASS。

- [ ] **Step 6: Commit**
```bash
git add -A server
git commit -m "feat(tool): ToolRegistry openapi 展开分支(读时1行→N个OpenApiToolCallback)+getToolCallbacks支持全source"
```

---

## Task 6: ToolAdminService（CRUD）

**Files:**
- Create: `server/src/main/java/com/hify/tool/dto/{CreateToolRequest,UpdateToolRequest,AuthHeaderInput,ToolAdminResponse,ToolAdminDetailResponse,OperationView}.java`
- Create: `server/src/main/java/com/hify/tool/service/ToolAdminService.java`
- Test: `server/src/test/java/com/hify/tool/service/ToolAdminServiceTest.java`

**Interfaces:**
- Consumes: `OpenApiSpecParser.parse`、`SecretCipher.encrypt`、`ToolMapper`、`CurrentUser`。
- Produces: `ToolAdminService` 方法 `create(CreateToolRequest, CurrentUser)→ToolAdminResponse`、`list()→List<ToolAdminResponse>`、`get(Long)→ToolAdminDetailResponse`、`update(Long, UpdateToolRequest)→ToolAdminResponse`、`delete(Long)`、`enable(Long)`、`disable(Long)`。

- [ ] **Step 1: 建 DTO**

`AuthHeaderInput.java`：
```java
package com.hify.tool.dto;

import jakarta.validation.constraints.NotBlank;

/** 鉴权头输入（value 明文，服务端立即加密）。 */
public record AuthHeaderInput(@NotBlank String name, @NotBlank String value) {}
```
`CreateToolRequest.java`：
```java
package com.hify.tool.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 注册自定义工具：粘贴 OpenAPI 文档 + 可选鉴权头。 */
public record CreateToolRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 500) String description,
        @NotBlank String specText,
        @Valid List<AuthHeaderInput> authHeaders) {}
```
`UpdateToolRequest.java`：内容同 `CreateToolRequest`（全量替换语义），类名换掉、注释「全量更新」。
`OperationView.java`：
```java
package com.hify.tool.dto;

/** 详情里的操作摘要（不含 inputSchema/parameters 细节）。 */
public record OperationView(String opName, String method, String pathTemplate, String description) {}
```
`ToolAdminResponse.java`：
```java
package com.hify.tool.dto;

import java.time.OffsetDateTime;

/** admin 列表/写返回。operationCount：openapi=操作数，builtin=null。 */
public record ToolAdminResponse(
        Long id, String name, String description, String source, boolean enabled,
        Integer operationCount, Long ownerId, OffsetDateTime createTime, OffsetDateTime updateTime) {}
```
`ToolAdminDetailResponse.java`：
```java
package com.hify.tool.dto;

import java.util.List;

/** admin 详情（供 T3b 编辑表单）。authHeaderNames 只回头名，绝不回明文值。 */
public record ToolAdminDetailResponse(
        Long id, String name, String description, String source, boolean enabled,
        String baseUrl, List<OperationView> operations, List<String> authHeaderNames, String rawSpec) {}
```

- [ ] **Step 2: 写失败测试（Mockito mock 全部依赖）**

`ToolAdminServiceTest.java`：
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
import com.hify.tool.service.openapi.OpenApiSpecParser;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import com.hify.tool.service.openapi.ParsedOpenApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolAdminServiceTest {

    private ToolMapper mapper;
    private OpenApiSpecParser parser;
    private SecretCipher cipher;
    private ToolAdminService service;
    private final CurrentUser admin = new CurrentUser(1L, "admin", "admin");

    @BeforeEach
    void setup() {
        mapper = mock(ToolMapper.class);
        parser = mock(OpenApiSpecParser.class);
        cipher = mock(SecretCipher.class);
        service = new ToolAdminService(mapper, parser, cipher);
    }

    private ParsedOpenApi parsed() {
        return new ParsedOpenApi("https://api.example.com",
                List.of(new OpenApiToolSpec.Operation("getPet", "GET", "/pets/{id}", "查", "{}",
                        List.of(new OpenApiToolSpec.Param("id", "path", true)))));
    }

    @Test
    void create_parsesEncryptsAndInserts_setsOwner() {
        when(parser.parse(any())).thenReturn(parsed());
        when(cipher.encrypt("k")).thenReturn("ENC");
        when(mapper.selectCount(any())).thenReturn(0L);

        CreateToolRequest req = new CreateToolRequest("petstore", "宠物", "SPEC",
                List.of(new AuthHeaderInput("X-API-Key", "k")));
        ToolAdminResponse resp = service.create(req, admin);

        ArgumentCaptor<Tool> saved = ArgumentCaptor.forClass(Tool.class);
        org.mockito.Mockito.verify(mapper).insert(saved.capture());
        Tool row = saved.getValue();
        assertThat(row.getSource()).isEqualTo("openapi");
        assertThat(row.getOwnerId()).isEqualTo(1L);
        assertThat(row.getSpec().authHeaders().get(0).valueEnc()).isEqualTo("ENC");
        assertThat(row.getSpec().rawSpec()).isEqualTo("SPEC");
        assertThat(resp.source()).isEqualTo("openapi");
        assertThat(resp.operationCount()).isEqualTo(1);
    }

    @Test
    void create_duplicateName_conflict() {
        when(parser.parse(any())).thenReturn(parsed());
        when(mapper.selectCount(any())).thenReturn(1L);
        CreateToolRequest req = new CreateToolRequest("petstore", "宠物", "SPEC", List.of());
        assertThatThrownBy(() -> service.create(req, admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(CommonError.CONFLICT));
    }

    @Test
    void get_openApiRow_returnsOperations_noSecretValues() {
        Tool row = openApiRow();
        when(mapper.selectById(9L)).thenReturn(row);
        ToolAdminDetailResponse detail = service.get(9L);
        assertThat(detail.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(detail.operations()).extracting(o -> o.opName()).contains("getPet");
        assertThat(detail.authHeaderNames()).containsExactly("X-API-Key");
        assertThat(detail.rawSpec()).isEqualTo("raw");
    }

    @Test
    void get_notFound_throws() {
        when(mapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.get(404L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(CommonError.NOT_FOUND));
    }

    @Test
    void update_builtinRow_rejected() {
        Tool builtin = new Tool();
        builtin.setId(1L);
        builtin.setName("http_request");
        builtin.setSource("builtin");
        when(mapper.selectById(1L)).thenReturn(builtin);
        assertThatThrownBy(() -> service.update(1L,
                new com.hify.tool.dto.UpdateToolRequest("http_request", "x", "SPEC", List.of())))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(CommonError.PARAM_INVALID));
    }

    @Test
    void delete_builtinRow_rejected() {
        Tool builtin = new Tool();
        builtin.setId(1L);
        builtin.setSource("builtin");
        when(mapper.selectById(1L)).thenReturn(builtin);
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(CommonError.PARAM_INVALID));
    }

    @Test
    void list_mapsOperationCount_builtinNull() {
        Tool builtin = new Tool();
        builtin.setId(1L); builtin.setName("http_request"); builtin.setSource("builtin"); builtin.setEnabled(true);
        Tool oa = openApiRow(); oa.setEnabled(true);
        when(mapper.selectList(any())).thenReturn(List.of(builtin, oa));
        List<ToolAdminResponse> list = service.list();
        assertThat(list).filteredOn(r -> r.source().equals("builtin"))
                .allSatisfy(r -> assertThat(r.operationCount()).isNull());
        assertThat(list).filteredOn(r -> r.source().equals("openapi"))
                .allSatisfy(r -> assertThat(r.operationCount()).isEqualTo(1));
    }

    private Tool openApiRow() {
        Tool row = new Tool();
        row.setId(9L);
        row.setName("petstore");
        row.setDescription("宠物");
        row.setSource("openapi");
        row.setEnabled(true);
        row.setOwnerId(1L);
        row.setSpec(new OpenApiToolSpec("https://api.example.com",
                List.of(new OpenApiToolSpec.AuthHeader("X-API-Key", "ENC")),
                List.of(new OpenApiToolSpec.Operation("getPet", "GET", "/pets/{id}", "查", "{}",
                        List.of(new OpenApiToolSpec.Param("id", "path", true)))),
                "raw"));
        return row;
    }
}
```
> 注：`BizException.getErrorCode()` 与 `mapper.selectCount(Wrapper)` 返回类型（`Long`/`long`）以现有代码为准，实现前核对；断言相应调整。

- [ ] **Step 3: 运行确认失败**

Run: `mvn -q -f server -Dtest=ToolAdminServiceTest test`
Expected: 编译失败（service 未建）。

- [ ] **Step 4: 实现 ToolAdminService**

`ToolAdminService.java`：
```java
package com.hify.tool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.crypto.SecretCipher;
import com.hify.infra.security.CurrentUser;
import com.hify.tool.dto.AuthHeaderInput;
import com.hify.tool.dto.CreateToolRequest;
import com.hify.tool.dto.OperationView;
import com.hify.tool.dto.ToolAdminDetailResponse;
import com.hify.tool.dto.ToolAdminResponse;
import com.hify.tool.dto.UpdateToolRequest;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.openapi.OpenApiSpecParser;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import com.hify.tool.service.openapi.ParsedOpenApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义工具注册表 admin CRUD（Admin 专属）。openapi 行可增删改；builtin 行只可启停、不可删改。
 * 凭据加密走 infra SecretCipher，只存密文；解析走 OpenApiSpecParser。@Transactional 只在写方法，内无外部 IO。
 */
@Service
public class ToolAdminService {

    private final ToolMapper toolMapper;
    private final OpenApiSpecParser parser;
    private final SecretCipher cipher;

    public ToolAdminService(ToolMapper toolMapper, OpenApiSpecParser parser, SecretCipher cipher) {
        this.toolMapper = toolMapper;
        this.parser = parser;
        this.cipher = cipher;
    }

    @Transactional
    public ToolAdminResponse create(CreateToolRequest req, CurrentUser current) {
        assertNameFree(req.name(), null);
        OpenApiToolSpec spec = buildSpec(req.specText(), req.authHeaders());
        Tool row = new Tool();
        row.setName(req.name());
        row.setDescription(req.description());
        row.setSource("openapi");
        row.setEnabled(true);
        row.setOwnerId(current.userId());
        row.setSpec(spec);
        toolMapper.insert(row);
        return toResponse(row);
    }

    public List<ToolAdminResponse> list() {
        return toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                        .orderByAsc(Tool::getSource).orderByAsc(Tool::getName))
                .stream().map(this::toResponse).toList();
    }

    public ToolAdminDetailResponse get(Long id) {
        Tool row = require(id);
        OpenApiToolSpec spec = row.getSpec();
        if (spec == null) {
            return new ToolAdminDetailResponse(row.getId(), row.getName(), row.getDescription(),
                    row.getSource(), Boolean.TRUE.equals(row.getEnabled()),
                    null, List.of(), List.of(), null);
        }
        List<OperationView> ops = spec.operations() == null ? List.of() : spec.operations().stream()
                .map(o -> new OperationView(o.opName(), o.method(), o.pathTemplate(), o.description())).toList();
        List<String> headerNames = spec.authHeaders() == null ? List.of() : spec.authHeaders().stream()
                .map(OpenApiToolSpec.AuthHeader::name).toList();
        return new ToolAdminDetailResponse(row.getId(), row.getName(), row.getDescription(),
                row.getSource(), Boolean.TRUE.equals(row.getEnabled()),
                spec.baseUrl(), ops, headerNames, spec.rawSpec());
    }

    @Transactional
    public ToolAdminResponse update(Long id, UpdateToolRequest req) {
        Tool row = require(id);
        assertOpenApi(row, "修改");
        assertNameFree(req.name(), id);
        row.setName(req.name());
        row.setDescription(req.description());
        row.setSpec(buildSpec(req.specText(), req.authHeaders()));
        toolMapper.updateById(row);
        return toResponse(row);
    }

    @Transactional
    public void delete(Long id) {
        Tool row = require(id);
        assertOpenApi(row, "删除");
        toolMapper.deleteById(id);
    }

    @Transactional
    public void enable(Long id) {
        setEnabled(id, true);
    }

    @Transactional
    public void disable(Long id) {
        setEnabled(id, false);
    }

    private void setEnabled(Long id, boolean enabled) {
        Tool row = require(id);
        row.setEnabled(enabled);
        toolMapper.updateById(row);
    }

    private OpenApiToolSpec buildSpec(String specText, List<AuthHeaderInput> authHeaders) {
        ParsedOpenApi parsed = parser.parse(specText);
        List<OpenApiToolSpec.AuthHeader> enc = new ArrayList<>();
        if (authHeaders != null) {
            for (AuthHeaderInput h : authHeaders) {
                enc.add(new OpenApiToolSpec.AuthHeader(h.name(), cipher.encrypt(h.value())));
            }
        }
        return new OpenApiToolSpec(parsed.baseUrl(), enc, parsed.operations(), specText);
    }

    private Tool require(Long id) {
        Tool row = toolMapper.selectById(id);
        if (row == null) {
            throw new BizException(CommonError.NOT_FOUND, "工具不存在");
        }
        return row;
    }

    private void assertOpenApi(Tool row, String action) {
        if (!"openapi".equals(row.getSource())) {
            throw new BizException(CommonError.PARAM_INVALID, "内置工具不可" + action);
        }
    }

    private void assertNameFree(String name, Long excludeId) {
        LambdaQueryWrapper<Tool> w = new LambdaQueryWrapper<Tool>().eq(Tool::getName, name);
        if (excludeId != null) {
            w.ne(Tool::getId, excludeId);
        }
        if (toolMapper.selectCount(w) > 0) {
            throw new BizException(CommonError.CONFLICT, "工具名已存在：" + name);
        }
    }

    private ToolAdminResponse toResponse(Tool row) {
        Integer opCount = (row.getSpec() != null && row.getSpec().operations() != null)
                ? row.getSpec().operations().size() : null;
        return new ToolAdminResponse(row.getId(), row.getName(), row.getDescription(),
                row.getSource(), Boolean.TRUE.equals(row.getEnabled()),
                opCount, row.getOwnerId(), row.getCreateTime(), row.getUpdateTime());
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn -q -f server -Dtest=ToolAdminServiceTest test`
Expected: 全 PASS。

- [ ] **Step 6: Commit**
```bash
git add -A server
git commit -m "feat(tool): ToolAdminService(自定义工具CRUD·解析+加密+重名/builtin守卫)"
```

---

## Task 7: AdminToolController

**Files:**
- Create: `server/src/main/java/com/hify/tool/controller/AdminToolController.java`
- Test: `server/src/test/java/com/hify/tool/controller/AdminToolControllerTest.java`（@WebMvcTest，仿现有 controller 切片测试；若项目无 controller 切片测试范式则用 MockMvc standalone）

**Interfaces:**
- Consumes: `ToolAdminService`、`CurrentUserHolder.current()`。
- Produces: REST 端点 `/api/v1/admin/tool/tools`（见 Global Constraints）。

- [ ] **Step 1: 实现 AdminToolController**

`AdminToolController.java`：
```java
package com.hify.tool.controller;

import com.hify.common.Result;
import com.hify.infra.security.CurrentUserHolder;
import com.hify.tool.dto.CreateToolRequest;
import com.hify.tool.dto.ToolAdminDetailResponse;
import com.hify.tool.dto.ToolAdminResponse;
import com.hify.tool.dto.UpdateToolRequest;
import com.hify.tool.service.ToolAdminService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 自定义工具注册表 admin 接口（仅 Admin，SecurityConfig 的 hasRole(ADMIN) 统一拦 /api/v1/admin/**）。
 * 协议层无业务逻辑、无 @Transactional、不注入 Mapper。启停用动作子资源 POST（不用 PATCH）。
 */
@RestController
@RequestMapping("/api/v1/admin/tool/tools")
public class AdminToolController {

    private final ToolAdminService toolAdminService;

    public AdminToolController(ToolAdminService toolAdminService) {
        this.toolAdminService = toolAdminService;
    }

    @GetMapping
    public Result<List<ToolAdminResponse>> list() {
        return Result.ok(toolAdminService.list());
    }

    @PostMapping
    public Result<ToolAdminResponse> create(@Valid @RequestBody CreateToolRequest request) {
        return Result.ok(toolAdminService.create(request, CurrentUserHolder.current()));
    }

    @GetMapping("/{id}")
    public Result<ToolAdminDetailResponse> get(@PathVariable Long id) {
        return Result.ok(toolAdminService.get(id));
    }

    @PutMapping("/{id}")
    public Result<ToolAdminResponse> update(@PathVariable Long id,
                                            @Valid @RequestBody UpdateToolRequest request) {
        return Result.ok(toolAdminService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        toolAdminService.delete(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        toolAdminService.enable(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        toolAdminService.disable(id);
        return Result.ok(null);
    }
}
```

- [ ] **Step 2: 控制器切片测试（照 AdminProviderControllerTest 范式）**

范式已确认 = `server/src/test/java/com/hify/provider/controller/AdminProviderControllerTest.java`：
`@WebMvcTest(AdminToolController.class)` + `@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})` + `@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")`；`@MockitoBean ToolAdminService`；`adminToken()` 用 `jwtService.generateToken(new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN))`，请求带 `header("Authorization", "Bearer " + adminToken())`。
至少覆盖：`GET /api/v1/admin/tool/tools` 返回 200 + `$.code`=200 + list；`POST /tools`（合法 body）返回 200 + `$.data.source`=openapi（service mock 返回固定 `ToolAdminResponse`）；**无 token → 401**（验证 admin 拦截生效）。

- [ ] **Step 3: 运行**

Run: `mvn -q -f server -Dtest=AdminToolControllerTest test`
Expected: PASS。

- [ ] **Step 4: Commit**
```bash
git add -A server
git commit -m "feat(tool): AdminToolController(/api/v1/admin/tool/tools CRUD+启停)"
```

---

## Task 8: 全量回归 + self-check 入档

**Files:**
- Modify: `docs/self-check.md`（追加 T3a 自检）

- [ ] **Step 1: 全量测试（含 Modularity/ArchUnit）**

Run: `mvn -q -f server clean test`
Expected: 全绿。特别核对：
- `ModularityTests`：tool 无对 provider 的依赖（cipher 已在 infra）；tool→infra 合法。
- `LayerRules`/ArchUnit：DTO 不 import entity（本轮 DTO 无 entity 引用）；controller 不注入 Mapper。
- 若 `spec` 列反序列化在真库查询触发问题，起服务 `mvn -f server spring-boot:run` 手验 `GET /api/v1/admin/tool/tools`（需 admin JWT）。

- [ ] **Step 2: 起服务冒烟（可选但推荐）**

`mvn -f server spring-boot:run`（默认连 hify 库）。用 admin 登录拿 JWT，`curl` 注册一个公网 spec（如 `https://petstore3.swagger.io/api/v3/openapi.json` 若可达；否则用内联最小 spec），确认：
- `POST /tools` 返回 200 + operationCount>0；
- `GET /tools` 含新行 + 内置两行；
- `GET /tools/{id}` 不回明文鉴权值；
- 到 Agent 应用勾选该工具试聊，看 tool_call 轨迹（端到端，属加分项，SSRF 禁内网故用公网 API）。

- [ ] **Step 3: self-check 入档**

`docs/self-check.md` 追加 T3a 段：交付项、测试计数、终审证据、留账（baseUrl 相对路径不支持、SSRF 禁内网、T3b 依赖的详情契约）。

- [ ] **Step 4: Commit**
```bash
git add -A
git commit -m "docs(self-check): T3a OpenAPI 自定义工具后端 自检入档"
```

---

## Self-Review（写完计划后对照 spec）

**Spec 覆盖检查**（逐节能否指到 Task）：
- §3.1 Tool 实体挂 spec → Task 3 ✓
- §3.2 spec jsonb 形状 → Task 2（record）+ Task 3（TypeHandler）✓
- §4 Admin CRUD 接口表 + §4.1 DTO + §4.2 写侧规则（builtin 拒改删/重名/启停任意）→ Task 6/7 ✓
- §5.1 ToolRegistry openapi 分支 + getToolCallbacks 改造 → Task 5 ✓
- §5.2 OpenApiSpecParser → Task 2 ✓
- §5.3 OpenApiToolCallback 执行 → Task 4 ✓
- §5.4 服务层组织（ToolService.listEnabled 不改）→ 未触碰，符合 ✓
- §6 错误码 13001 复用通用段 → Task 2（ToolError）+ Task 6（复用 CommonError）✓
- §7 cipher 提到 infra + provider 迁移 → Task 1 ✓
- §8 测试 → 各 Task TDD + Task 8 回归 ✓

**Placeholder 扫描**：无 TBD/TODO；每个 code 步给了完整代码；测试步给了完整断言。少数「以现有代码为准核对访问器名」的注记（BizException.getErrorCode、mapper.selectCount 返回型、既有 ToolRegistry 测试构造、controller 切片范式）是**执行时必须现场核实的点**，非占位——已明确指出 grep 命令与调整方式。

**类型一致性**：`OpenApiToolSpec`/`Operation`/`Param`/`AuthHeader` 字段名跨 Task 2/3/4/5/6 一致；`ToolRegistry` 新构造签名（5 参）在 Task 5 定义、Task 5 Step 4 提示补既有测试；`ToolAdminService` 方法签名 Task 6 定义、Task 7 消费一致；`SecretCipher.encrypt/decrypt` Task 1 定义、Task 4/5/6 消费一致。
