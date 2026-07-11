# Workflow W3b HTTP 请求节点 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** infra 出站 HTTP 地基（SSRF 防护 + JDK HttpClient 收口）+ workflow `http` 节点，非 2xx 输出 status 交给 condition 节点分流。

**Architecture:** `infra/outbound` 新子包（infra 是 OPEN 模块，workflow 直接注入）：`SsrfValidator` 对 DNS 解析后的全部 IP 校验，`OutboundHttpClient` 封装 JDK HttpClient（`Redirect.NEVER`、双超时、响应截断）。workflow 侧纯加 executor（W2 模式），引擎零改动、零迁移。

**Tech Stack:** JDK 21 `java.net.http.HttpClient`（零新依赖）/ Spring Boot 3.x / JUnit5 + Mockito / `com.sun.net.httpserver.HttpServer`（测试桩）。

**Spec:** `docs/superpowers/specs/2026-07-11-workflow-w3b-http-node-design.md`

## Global Constraints

- 与架构文档冲突的代码视为错误；本轮**零 Flyway 迁移**
- 测试命令在 `server/` 下跑；**判定结果看退出码，禁止 grep "BUILD SUCCESS"**（`-q` 会静音）
- 错误码复用通用段（已现场核对 api-standards §5.3/5.4）：SSRF 拦截/URL 非法 → `CommonError.PARAM_INVALID`(10001)，网络 IO 失败 → `CommonError.DEPENDENCY_UNAVAILABLE`(10008)，均带定制 message；**不新开错误码段**
- 配置外化 `hify.outbound.http.*`，默认值：connect-timeout-ms 5000 / read-timeout-ms 15000 / max-response-bytes 65536
- 敏感请求头脱敏白名单（大小写不敏感）：`Authorization`、`Cookie`、`Proxy-Authorization`、`X-Api-Key` → inputs 落库为 `***`
- 节点重试 0；引擎/服务类禁止 @Transactional；提交信息用中文 conventional commits

---

### Task 1: OutboundProperties + SsrfValidator

**Files:**
- Create: `server/src/main/java/com/hify/infra/outbound/OutboundProperties.java`
- Create: `server/src/main/java/com/hify/infra/outbound/SsrfValidator.java`
- Modify: `server/src/main/resources/application.yml`（`hify:` 下加 `outbound:` 段，与 `workflow:` 平级）
- Test: `server/src/test/java/com/hify/infra/outbound/SsrfValidatorTest.java`

**Interfaces:**
- Produces: `OutboundProperties`（getConnectTimeoutMs/getReadTimeoutMs/getMaxResponseBytes，int）；`SsrfValidator.validate(String host)`——命中禁区抛 `BizException(PARAM_INVALID)`，解析失败抛 `BizException(PARAM_INVALID)`；包级构造 `SsrfValidator(Function<String, InetAddress[]> resolver)` 供测试注入假解析。Task 2 消费。

- [x] **Step 1: 写失败测试**

新建 `SsrfValidatorTest.java`：

```java
package com.hify.infra.outbound;

import com.hify.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsrfValidatorTest {

    private static InetAddress[] addr(String ip) {
        try {
            return new InetAddress[]{InetAddress.getByName(ip)};
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private SsrfValidator withResolved(String ip) {
        return new SsrfValidator(host -> addr(ip));
    }

    @Test
    void 公网IP_放行() {
        assertDoesNotThrow(() -> withResolved("93.184.216.34").validate("example.com"));
    }

    @Test
    void 回环_拦截() {
        assertThrows(BizException.class, () -> withResolved("127.0.0.1").validate("localhost"));
        assertThrows(BizException.class, () -> withResolved("::1").validate("ip6-localhost"));
    }

    @Test
    void RFC1918三段_全拦截() {
        assertThrows(BizException.class, () -> withResolved("10.1.2.3").validate("a"));
        assertThrows(BizException.class, () -> withResolved("172.16.0.1").validate("b"));
        assertThrows(BizException.class, () -> withResolved("172.31.255.254").validate("c"));
        assertThrows(BizException.class, () -> withResolved("192.168.1.1").validate("d"));
    }

    @Test
    void linkLocal云元数据_拦截() {
        assertThrows(BizException.class, () -> withResolved("169.254.169.254").validate("metadata"));
    }

    @Test
    void any与组播_拦截() {
        assertThrows(BizException.class, () -> withResolved("0.0.0.0").validate("any"));
        assertThrows(BizException.class, () -> withResolved("224.0.0.1").validate("mcast"));
    }

    @Test
    void IPv6_ULA_fc00段_拦截() {
        assertThrows(BizException.class, () -> withResolved("fd12:3456::1").validate("ula"));
    }

    @Test
    void 域名解析出多IP_任一命中即拦截() {
        SsrfValidator v = new SsrfValidator(host -> new InetAddress[]{
                addr("93.184.216.34")[0], addr("192.168.1.1")[0]});   // 公网+私网混合（DNS 指回内网）
        BizException ex = assertThrows(BizException.class, () -> v.validate("evil.example.com"));
        assertTrue(ex.getMessage().contains("evil.example.com"));
    }

    @Test
    void 域名无法解析_拦截且提示() {
        SsrfValidator v = new SsrfValidator(host -> { throw new IllegalStateException("unknown host"); });
        BizException ex = assertThrows(BizException.class, () -> v.validate("no-such.example"));
        assertTrue(ex.getMessage().contains("no-such.example"));
    }
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
cd server && mvn -Dtest=SsrfValidatorTest test
```
Expected: 编译失败「找不到符号 SsrfValidator」。退出码非 0。

- [x] **Step 3: 实现**

新建 `OutboundProperties.java`：

```java
package com.hify.infra.outbound;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 出站 HTTP 客户端配置（CLAUDE.md：外部调用必须有超时且外化）。HTTP 节点/自定义工具/MCP 共用。 */
@Component
@ConfigurationProperties(prefix = "hify.outbound.http")
public class OutboundProperties {

    /** 连接超时（毫秒）。 */
    private int connectTimeoutMs = 5000;
    /** 响应读取超时（毫秒）。 */
    private int readTimeoutMs = 15000;
    /** 响应体大小上限（字节），超出截断——防大响应撑爆 node_run jsonb 与内存。 */
    private int maxResponseBytes = 65536;

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
}
```

新建 `SsrfValidator.java`：

```java
package com.hify.infra.outbound;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.Function;

/**
 * SSRF 防护（deployment.md §5）：对 DNS 解析后的<b>全部</b> IP 校验，任一命中禁区即拒绝——
 * 回环 / RFC1918 私网 / link-local(169.254 云元数据) / any / 组播 / IPv6 ULA(fc00::/7)。
 * 容器服务名（postgres/sandbox）解析结果是容器网段私网 IP，被私网规则自动覆盖。
 * 已知边界（spec §3）：不做 DNS pinning，一期威胁模型（内网部署+受信团队）可接受。
 * 内网白名单为推迟项（spec §1），真有需求时在此查 system_setting 放行。
 */
@Component
public class SsrfValidator {

    private final Function<String, InetAddress[]> resolver;

    public SsrfValidator() {
        this(SsrfValidator::resolveAll);
    }

    /** 测试注入假解析用。 */
    SsrfValidator(Function<String, InetAddress[]> resolver) {
        this.resolver = resolver;
    }

    /** host 合法则静默返回；解析失败或任一解析 IP 命中禁区抛 BizException(10001)。 */
    public void validate(String host) {
        InetAddress[] addresses;
        try {
            addresses = resolver.apply(host);
        } catch (RuntimeException e) {
            throw new BizException(CommonError.PARAM_INVALID, "目标域名无法解析：" + host);
        }
        for (InetAddress a : addresses) {
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                    || a.isAnyLocalAddress() || a.isMulticastAddress() || isIpv6UniqueLocal(a)) {
                throw new BizException(CommonError.PARAM_INVALID,
                        "目标地址禁止访问（内网/保留地址）：" + host);
            }
        }
    }

    /** IPv6 ULA fc00::/7——Java 的 isSiteLocalAddress 只认已废弃的 fec0::/10，须手动补。 */
    private static boolean isIpv6UniqueLocal(InetAddress a) {
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc;
    }

    private static InetAddress[] resolveAll(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`application.yml` 在 `hify:` 段内（与 `workflow:` 平级）追加：

```yaml
  outbound:
    http:
      connect-timeout-ms: ${HIFY_OUTBOUND_HTTP_CONNECT_TIMEOUT_MS:5000}
      read-timeout-ms: ${HIFY_OUTBOUND_HTTP_READ_TIMEOUT_MS:15000}
      max-response-bytes: ${HIFY_OUTBOUND_HTTP_MAX_RESPONSE_BYTES:65536}
```

- [x] **Step 4: 跑测试确认通过**

```bash
cd server && mvn -Dtest=SsrfValidatorTest test
```
Expected: 8 条全绿，退出码 0。

- [x] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/infra/outbound/OutboundProperties.java \
        server/src/main/java/com/hify/infra/outbound/SsrfValidator.java \
        server/src/main/resources/application.yml \
        server/src/test/java/com/hify/infra/outbound/SsrfValidatorTest.java
git commit -m "feat(infra): 出站配置与 SSRF 校验器（DNS 解析后全 IP 校验，含 IPv6 ULA）"
```

---

### Task 2: OutboundHttpClient + 本地桩集成测试

**Files:**
- Create: `server/src/main/java/com/hify/infra/outbound/OutboundResponse.java`
- Create: `server/src/main/java/com/hify/infra/outbound/OutboundHttpClient.java`
- Test: `server/src/test/java/com/hify/infra/outbound/OutboundHttpClientTest.java`

**Interfaces:**
- Consumes: `SsrfValidator.validate(String)`、`OutboundProperties`（Task 1）
- Produces: `record OutboundResponse(int status, String body, Map<String, String> headers)`；`OutboundResponse send(String method, String url, Map<String, String> headers, String body)`——method 已由调用方保证在 GET/POST/PUT/DELETE 白名单；URL scheme 非 http/https 或含受限头抛 `BizException(PARAM_INVALID)`；网络 IO 失败/超时抛 `BizException(DEPENDENCY_UNAVAILABLE)`；任何 HTTP 响应（含 3xx/4xx/5xx）正常返回。Task 4 消费。

- [x] **Step 1: 写失败测试**

新建 `OutboundHttpClientTest.java`（纯 JUnit 不起 Spring；`mock(SsrfValidator.class)` 的 validate 默认 no-op，绕过 127.0.0.1 拦截以连本地桩）：

```java
package com.hify.infra.outbound;

import com.hify.common.exception.BizException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OutboundHttpClientTest {

    private static HttpServer server;
    private static String base;
    private static OutboundHttpClient client;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> respond(ex, 200, "{\"weather\":\"晴\"}"));
        server.createContext("/missing", ex -> respond(ex, 404, "not found"));
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1/target");
            respond(ex, 302, "");
        });
        server.createContext("/big", ex -> respond(ex, 200, "x".repeat(1000)));
        server.createContext("/slow", ex -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
            respond(ex, 200, "late");
        });
        server.createContext("/echo", ex -> {
            String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(ex, 200, ex.getRequestMethod() + ":" + reqBody);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        OutboundProperties props = new OutboundProperties();
        props.setReadTimeoutMs(1000);       // /slow 睡 3s 必超时
        props.setMaxResponseBytes(100);     // /big 返回 1000 字节必截断
        client = new OutboundHttpClient(mock(SsrfValidator.class), props);
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void GET成功_status与body如实返回() {
        OutboundResponse resp = client.send("GET", base + "/ok", Map.of(), null);
        assertEquals(200, resp.status());
        assertEquals("{\"weather\":\"晴\"}", resp.body());
    }

    @Test
    void 四百四_不抛异常_原样输出() {
        OutboundResponse resp = client.send("GET", base + "/missing", Map.of(), null);
        assertEquals(404, resp.status());
        assertEquals("not found", resp.body());
    }

    @Test
    void 三零二_不跟随_原样输出Location() {
        OutboundResponse resp = client.send("GET", base + "/redirect", Map.of(), null);
        assertEquals(302, resp.status());
        assertEquals("http://127.0.0.1/target", resp.headers().get("location"));
    }

    @Test
    void 响应超上限_截断到maxResponseBytes() {
        OutboundResponse resp = client.send("GET", base + "/big", Map.of(), null);
        assertEquals(100, resp.body().length());
    }

    @Test
    void 读超时_抛Biz依赖不可用() {
        BizException ex = assertThrows(BizException.class,
                () -> client.send("GET", base + "/slow", Map.of(), null));
        assertEquals(10008, ex.errorCode().code());
    }

    @Test
    void POST带body_如实发出() {
        OutboundResponse resp = client.send("POST", base + "/echo", Map.of(), "{\"a\":1}");
        assertEquals("POST:{\"a\":1}", resp.body());
    }

    @Test
    void scheme非http_拒绝() {
        BizException ex = assertThrows(BizException.class,
                () -> client.send("GET", "ftp://example.com/x", Map.of(), null));
        assertEquals(10001, ex.errorCode().code());
    }

    @Test
    void 受限头_拒绝并转Biz() {   // JDK HttpClient 禁止设置 Host 等受限头
        BizException ex = assertThrows(BizException.class,
                () -> client.send("GET", base + "/ok", Map.of("Host", "fake.com"), null));
        assertEquals(10001, ex.errorCode().code());
        assertTrue(ex.getMessage().contains("Host"));
    }
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
cd server && mvn -Dtest=OutboundHttpClientTest test
```
Expected: 编译失败「找不到符号 OutboundHttpClient/OutboundResponse」。退出码非 0。

- [x] **Step 3: 实现**

新建 `OutboundResponse.java`：

```java
package com.hify.infra.outbound;

import java.util.Map;

/** 出站请求结果：status 原样（含 3xx/4xx/5xx），body 已按上限截断，headers 键为小写、多值逗号连接。 */
public record OutboundResponse(int status, String body, Map<String, String> headers) {
}
```

新建 `OutboundHttpClient.java`：

```java
package com.hify.infra.outbound;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 出站 HTTP 统一收口（deployment.md §5：tool/workflow 禁止自建客户端）。
 * Redirect.NEVER（3xx 原样返回，封死重定向绕过 SSRF，spec 拍板）；连接/读取双超时外化；
 * 响应体按 max-response-bytes 硬截断（UTF-8 多字节字符可能截出替换符，可接受）。
 * 发请求前必过 SsrfValidator。虚拟线程环境下同步阻塞调用即可。
 */
@Component
public class OutboundHttpClient {

    private final SsrfValidator ssrfValidator;
    private final OutboundProperties props;
    private final HttpClient client;

    public OutboundHttpClient(SsrfValidator ssrfValidator, OutboundProperties props) {
        this.ssrfValidator = ssrfValidator;
        this.props = props;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
    }

    /** method 由调用方保证在白名单；任何 HTTP 响应都正常返回，只有网络/校验失败才抛。 */
    public OutboundResponse send(String method, String url, Map<String, String> headers, String body) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BizException(CommonError.PARAM_INVALID, "URL 非法：" + url);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new BizException(CommonError.PARAM_INVALID, "URL 仅支持 http/https：" + url);
        }
        if (uri.getHost() == null) {
            throw new BizException(CommonError.PARAM_INVALID, "URL 缺少主机名：" + url);
        }
        ssrfValidator.validate(uri.getHost());

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(props.getReadTimeoutMs()));
        try {
            if (headers != null) {
                headers.forEach(builder::header);
            }
        } catch (IllegalArgumentException e) {
            // JDK 拒绝受限头（Host/Connection/Content-Length 等）
            throw new BizException(CommonError.PARAM_INVALID, "请求头不允许设置：" + e.getMessage());
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest request = builder.method(method, publisher).build();

        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "HTTP 请求失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "HTTP 请求被中断");
        }
        return new OutboundResponse(response.statusCode(), truncate(response.body()), flatten(response));
    }

    private String truncate(byte[] raw) {
        int limit = Math.min(raw.length, props.getMaxResponseBytes());
        return new String(raw, 0, limit, StandardCharsets.UTF_8);
    }

    /** 响应头拍平：键小写（HttpHeaders 本就规整为小写）、多值逗号连接。 */
    private static Map<String, String> flatten(HttpResponse<byte[]> response) {
        Map<String, String> flat = new LinkedHashMap<>();
        response.headers().map().forEach((k, v) -> flat.put(k.toLowerCase(), String.join(",", v)));
        return flat;
    }
}
```

- [x] **Step 4: 跑测试确认通过**

```bash
cd server && mvn -Dtest=OutboundHttpClientTest test
```
Expected: 8 条全绿，退出码 0。

- [x] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/infra/outbound/OutboundResponse.java \
        server/src/main/java/com/hify/infra/outbound/OutboundHttpClient.java \
        server/src/test/java/com/hify/infra/outbound/OutboundHttpClientTest.java
git commit -m "feat(infra): 出站 HTTP 客户端（Redirect.NEVER/双超时/响应截断，本地桩集成测试）"
```

---

### Task 3: NodeType.HTTP + GraphValidator http 校验

**Files:**
- Modify: `server/src/main/java/com/hify/workflow/constant/NodeType.java`
- Modify: `server/src/main/java/com/hify/workflow/service/engine/GraphValidator.java`
- Test: `server/src/test/java/com/hify/workflow/service/engine/GraphValidatorTest.java`（追加用例）

**Interfaces:**
- Produces: `NodeType.HTTP`（value=`"http"`）；validator 规则：method 必填且（大小写不敏感）∈ GET/POST/PUT/DELETE、url 必填非空、headers 若存在须为 Map。

- [ ] **Step 1: 写失败测试**

在 `GraphValidatorTest.java` 追加：

```java
// ==== W3b: http 节点校验 ====

private GraphDef httpGraph(Map<String, Object> httpData) {
    return new GraphDef(List.of(
            new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "q", "required", true)))),
            new GraphNode("http_1", "http", httpData),
            new GraphNode("end", "end", Map.of("outputs", List.of(Map.of("name", "r", "value", "{{http_1.body}}"))))),
            List.of(new GraphEdge("start", "http_1"), new GraphEdge("http_1", "end")));
}

@Test
void http节点_合法配置_通过_method大小写不敏感() {
    assertDoesNotThrow(() -> validator.validateAndOrder(httpGraph(
            Map.of("method", "get", "url", "https://api.example.com/x?q={{start.q}}"))));
    assertDoesNotThrow(() -> validator.validateAndOrder(httpGraph(Map.of(
            "method", "POST", "url", "https://api.example.com/x",
            "headers", Map.of("Content-Type", "application/json"), "body", "{\"q\":\"{{start.q}}\"}"))));
}

@Test
void http节点_缺method_拒绝() {
    BizException ex = assertThrows(BizException.class,
            () -> validator.validateAndOrder(httpGraph(Map.of("url", "https://a.com"))));
    assertTrue(ex.getMessage().contains("method"));
}

@Test
void http节点_method不在白名单_拒绝() {
    BizException ex = assertThrows(BizException.class,
            () -> validator.validateAndOrder(httpGraph(Map.of("method", "PATCH", "url", "https://a.com"))));
    assertTrue(ex.getMessage().contains("PATCH"));
}

@Test
void http节点_缺url_拒绝() {
    BizException ex = assertThrows(BizException.class,
            () -> validator.validateAndOrder(httpGraph(Map.of("method", "GET"))));
    assertTrue(ex.getMessage().contains("url"));
}

@Test
void http节点_headers非map_拒绝() {
    BizException ex = assertThrows(BizException.class,
            () -> validator.validateAndOrder(httpGraph(Map.of(
                    "method", "GET", "url", "https://a.com", "headers", "Authorization: x"))));
    assertTrue(ex.getMessage().contains("headers"));
}
```

（若该测试类未静态导入 `assertDoesNotThrow`，补 `import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;`。）

- [ ] **Step 2: 跑测试确认失败**

```bash
cd server && mvn -Dtest=GraphValidatorTest test
```
Expected: 合法用例报「未知节点类型：http」。退出码非 0。

- [ ] **Step 3: 实现**

`NodeType.java`（CONDITION 与 END 之间加一行）：

```java
    KNOWLEDGE_RETRIEVAL("knowledge-retrieval"),
    CONDITION("condition"),
    HTTP("http"),
    END("end");
```

`GraphValidator.java` 三处。(a) `CONDITION_OPERATORS` 之后加常量：

```java
    /** http 节点 method 白名单（spec §2）。 */
    static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE");
```

(b) 节点循环里（condition 分支之后）加：

```java
            if (NodeType.HTTP.value().equals(n.type())) {
                requireHttpFields(n);
            }
```

(c) 私有方法（`requireConditionFields` 之后）：

```java
    /** http 节点字段校验；url 的 scheme 与 SSRF 属运行时校验（url 可含模板变量，保存时只查必填）。 */
    private void requireHttpFields(GraphNode n) {
        Object method = n.data() == null ? null : n.data().get("method");
        if (method == null || String.valueOf(method).isBlank()) {
            throw invalid("http 节点 " + n.id() + " 缺少 method");
        }
        if (!HTTP_METHODS.contains(String.valueOf(method).toUpperCase())) {
            throw invalid("http 节点 " + n.id() + " 的 method 非法：" + method);
        }
        Object url = n.data().get("url");
        if (url == null || String.valueOf(url).isBlank()) {
            throw invalid("http 节点 " + n.id() + " 缺少 url");
        }
        Object headers = n.data().get("headers");
        if (headers != null && !(headers instanceof Map)) {
            throw invalid("http 节点 " + n.id() + " 的 headers 必须是对象");
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd server && mvn -Dtest=GraphValidatorTest test
```
Expected: 全绿（W1/W2/W3a 既有用例不回归），退出码 0。

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/workflow/constant/NodeType.java \
        server/src/main/java/com/hify/workflow/service/engine/GraphValidator.java \
        server/src/test/java/com/hify/workflow/service/engine/GraphValidatorTest.java
git commit -m "feat(workflow): http 节点类型与图校验（method 白名单/url 必填/headers 类型）"
```

---

### Task 4: HttpNodeExecutor

**Files:**
- Create: `server/src/main/java/com/hify/workflow/service/engine/HttpNodeExecutor.java`
- Test: `server/src/test/java/com/hify/workflow/service/engine/HttpNodeExecutorTest.java`

**Interfaces:**
- Consumes: `OutboundHttpClient.send(method, url, headers, body)` → `OutboundResponse(status, body, headers)`（Task 2）；`NodeType.HTTP`（Task 3）；`RunContext.render`
- Produces: Spring `@Component`，`type()="http"`，outputs=`{status: Integer, body: String, headers: Map}`，inputs=`{method, url(渲染后), headers(渲染后+脱敏), body(渲染后)}`。

- [ ] **Step 1: 写失败测试**

新建 `HttpNodeExecutorTest.java`：

```java
package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.infra.outbound.OutboundResponse;
import com.hify.workflow.dto.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpNodeExecutorTest {

    private OutboundHttpClient http;
    private HttpNodeExecutor executor;
    private RunContext ctx;

    @BeforeEach
    void setUp() {
        http = mock(OutboundHttpClient.class);
        executor = new HttpNodeExecutor(http);
        ctx = new RunContext(7L, 42L);
        ctx.putOutput("start", Map.of("uid", "u1", "token", "secret-token"));
    }

    private GraphNode node(Map<String, Object> data) {
        return new GraphNode("http_1", "http", data);
    }

    @Test
    void 渲染url与headers_输出status_body_headers() {
        when(http.send(eq("GET"), eq("https://api.example.com/u/u1"),
                eq(Map.of("Authorization", "Bearer secret-token")), isNull()))
                .thenReturn(new OutboundResponse(200, "{\"name\":\"张三\"}", Map.of("content-type", "application/json")));

        NodeResult result = executor.execute(node(Map.of(
                "method", "get",
                "url", "https://api.example.com/u/{{start.uid}}",
                "headers", Map.of("Authorization", "Bearer {{start.token}}"))), ctx);

        assertEquals(200, result.outputs().get("status"));
        assertEquals("{\"name\":\"张三\"}", result.outputs().get("body"));
        assertEquals("application/json",
                ((Map<?, ?>) result.outputs().get("headers")).get("content-type"));
        assertEquals("https://api.example.com/u/u1", result.inputs().get("url"));
    }

    @Test
    void 敏感头落inputs前脱敏_发出去的不脱敏() {
        when(http.send(anyString(), anyString(), anyMap(), any()))
                .thenReturn(new OutboundResponse(200, "", Map.of()));

        NodeResult result = executor.execute(node(Map.of(
                "method", "GET", "url", "https://a.com",
                "headers", Map.of("Authorization", "Bearer secret-token", "X-Trace", "t1"))), ctx);

        Map<?, ?> loggedHeaders = (Map<?, ?>) result.inputs().get("headers");
        assertEquals("***", loggedHeaders.get("Authorization"));   // 脱敏
        assertEquals("t1", loggedHeaders.get("X-Trace"));          // 普通头保留
    }

    @Test
    void 四百四_节点成功_status如实() {
        when(http.send(anyString(), anyString(), anyMap(), any()))
                .thenReturn(new OutboundResponse(404, "not found", Map.of()));

        NodeResult result = executor.execute(node(Map.of("method", "GET", "url", "https://a.com/x")), ctx);
        assertEquals(404, result.outputs().get("status"));
    }

    @Test
    void GET忽略body_传null给客户端() {
        when(http.send(eq("GET"), anyString(), anyMap(), isNull()))
                .thenReturn(new OutboundResponse(200, "", Map.of()));

        NodeResult result = executor.execute(node(Map.of(
                "method", "GET", "url", "https://a.com", "body", "{\"ignored\":1}")), ctx);
        assertNull(result.inputs().get("body"));   // inputs 里也是 null（如实反映实际发送）
    }

    @Test
    void 客户端抛Biz_转NodeExecutionException_inputs已脱敏() {
        when(http.send(anyString(), anyString(), anyMap(), any()))
                .thenThrow(new BizException(CommonError.PARAM_INVALID, "目标地址禁止访问（内网/保留地址）：localhost"));

        NodeExecutionException ex = assertThrows(NodeExecutionException.class,
                () -> executor.execute(node(Map.of(
                        "method", "GET", "url", "http://localhost:8080/x",
                        "headers", Map.of("Cookie", "sid=abc"))), ctx));

        assertEquals("***", ((Map<?, ?>) ex.inputs().get("headers")).get("Cookie"));
        assertEquals(BizException.class, ex.getCause().getClass());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd server && mvn -Dtest=HttpNodeExecutorTest test
```
Expected: 编译失败「找不到符号 HttpNodeExecutor」。退出码非 0。

- [ ] **Step 3: 实现**

新建 `HttpNodeExecutor.java`：

```java
package com.hify.workflow.service.engine;

import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.infra.outbound.OutboundResponse;
import com.hify.workflow.constant.NodeType;
import com.hify.workflow.dto.GraphNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 节点：渲染 url/headers/body → infra 出站客户端（内含 SSRF 校验/超时/截断/不跟随重定向）
 * → 输出 {status, body, headers}。拿到任何 HTTP 响应都算节点成功（spec 拍板：非 2xx 交给
 * condition 节点分流）；仅 SSRF 拦截/URL 非法/网络失败/超时才是节点失败。
 * 敏感请求头落 node_run.inputs 前脱敏（token 不进数据库）。
 */
@Component
public class HttpNodeExecutor implements NodeExecutor {

    /** 落库前脱敏的请求头（小写比较）。 */
    private static final Set<String> SENSITIVE_HEADERS =
            Set.of("authorization", "cookie", "proxy-authorization", "x-api-key");

    private final OutboundHttpClient http;

    public HttpNodeExecutor(OutboundHttpClient http) {
        this.http = http;
    }

    @Override
    public String type() {
        return NodeType.HTTP.value();
    }

    @Override
    public NodeResult execute(GraphNode node, RunContext ctx) {
        String method = String.valueOf(node.data().get("method")).toUpperCase();   // validator 已保证白名单
        String url = ctx.render(String.valueOf(node.data().get("url")));
        Map<String, String> headers = renderHeaders(node.data().get("headers"), ctx);
        Object rawBody = node.data().get("body");
        boolean bodyAllowed = "POST".equals(method) || "PUT".equals(method);
        String body = bodyAllowed && rawBody != null ? ctx.render(String.valueOf(rawBody)) : null;

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("method", method);
        inputs.put("url", url);
        inputs.put("headers", mask(headers));
        inputs.put("body", body);

        try {
            OutboundResponse resp = http.send(method, url, headers, body);
            return new NodeResult(inputs,
                    Map.of("status", resp.status(), "body", resp.body(), "headers", resp.headers()));
        } catch (Exception e) {
            // 渲染已成功、请求才失败：脱敏后的实际请求随异常落 node_run.inputs 供排障
            throw new NodeExecutionException(inputs, e);
        }
    }

    private Map<String, String> renderHeaders(Object raw, RunContext ctx) {
        Map<String, String> rendered = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {   // validator 已保证类型
            map.forEach((k, v) -> rendered.put(String.valueOf(k), ctx.render(String.valueOf(v))));
        }
        return rendered;
    }

    /** 敏感头掩码（排障能看到发了哪些头，token 不进库）。 */
    private static Map<String, String> mask(Map<String, String> headers) {
        Map<String, String> masked = new LinkedHashMap<>();
        headers.forEach((k, v) ->
                masked.put(k, SENSITIVE_HEADERS.contains(k.toLowerCase()) ? "***" : v));
        return masked;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd server && mvn -Dtest=HttpNodeExecutorTest test
```
Expected: 5 条全绿，退出码 0。

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/workflow/service/engine/HttpNodeExecutor.java \
        server/src/test/java/com/hify/workflow/service/engine/HttpNodeExecutorTest.java
git commit -m "feat(workflow): HTTP 节点 executor（非2xx不失败，敏感头落库脱敏）"
```

---

### Task 5: 集成测试（http → condition 分流全链路）

**Files:**
- Modify: `server/src/test/java/com/hify/workflow/WorkflowRunFlowTest.java`（追加 @MockitoBean 与两个测试方法）

**Interfaces:**
- Consumes: Task 1–4 全部产物；既有 `llmCaller` 桩、W3a condition 能力
- Produces: 无新接口，纯验证。

- [ ] **Step 1: 写测试**

`WorkflowRunFlowTest` 类内追加 mock（`knowledgeFacade` 声明之后）：

```java
    @MockitoBean
    private com.hify.infra.outbound.OutboundHttpClient outboundHttpClient;
```

追加图构造与两个测试方法（放 W3a 用例之后）：

```java
    /** W3b：start → http → if(status==200) → llm_ok / llm_fb → end 的分流图。 */
    private GraphDef httpBranchGraph() {
        return new GraphDef(List.of(
                new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "uid", "required", true)))),
                new GraphNode("http_1", "http", Map.of("method", "GET",
                        "url", "https://api.example.com/u/{{start.uid}}")),
                new GraphNode("if_1", "condition", Map.of("left", "{{http_1.status}}", "operator", "==", "right", "200")),
                new GraphNode("llm_ok", "llm", Map.of("modelId", "3", "userPrompt", "总结用户信息：{{http_1.body}}")),
                new GraphNode("llm_fb", "llm", Map.of("modelId", "3", "userPrompt", "接口异常（{{http_1.status}}），礼貌致歉")),
                new GraphNode("end", "end", Map.of("outputs", List.of(
                        Map.of("name", "answer", "value", "{{llm_ok.text}}{{llm_fb.text}}"))))),
                List.of(new GraphEdge("start", "http_1"), new GraphEdge("http_1", "if_1"),
                        new GraphEdge("if_1", "llm_ok", "true"),
                        new GraphEdge("if_1", "llm_fb", "false"),
                        new GraphEdge("llm_ok", "end"), new GraphEdge("llm_fb", "end")));
    }

    @Test
    void HTTP两百_body注入下游_走成功路() {
        when(outboundHttpClient.send(eq("GET"), eq("https://api.example.com/u/u1"), any(), any()))
                .thenReturn(new com.hify.infra.outbound.OutboundResponse(
                        200, "{\"name\":\"张三\"}", Map.of()));
        when(llmCaller.call(any(), any(), eq("总结用户信息：{\"name\":\"张三\"}")))
                .thenReturn(new LlmCallResult("用户是张三", 10, 5));

        draftService.saveDraft(appId, httpBranchGraph(), owner);
        RunResponse resp = runService.run(appId, Map.of("uid", "u1"), owner);

        assertEquals("succeeded", resp.status());
        assertEquals("用户是张三", resp.outputs().get("answer"));
        var byId = resp.nodeRuns().stream()
                .collect(java.util.stream.Collectors.toMap(n -> n.nodeId(), n -> n.status()));
        assertEquals("succeeded", byId.get("llm_ok"));
        assertEquals("skipped", byId.get("llm_fb"));
    }

    @Test
    void HTTP五百_status分流走兜底路_节点不失败() {
        when(outboundHttpClient.send(eq("GET"), any(), any(), any()))
                .thenReturn(new com.hify.infra.outbound.OutboundResponse(500, "boom", Map.of()));
        when(llmCaller.call(any(), any(), eq("接口异常（500），礼貌致歉")))
                .thenReturn(new LlmCallResult("抱歉，服务暂时不可用", 8, 4));

        draftService.saveDraft(appId, httpBranchGraph(), owner);
        RunResponse resp = runService.run(appId, Map.of("uid", "u1"), owner);

        assertEquals("succeeded", resp.status());   // 非 2xx 不是失败（spec 拍板）
        assertEquals("抱歉，服务暂时不可用", resp.outputs().get("answer"));
        var byId = resp.nodeRuns().stream()
                .collect(java.util.stream.Collectors.toMap(n -> n.nodeId(), n -> n.status()));
        assertEquals("succeeded", byId.get("http_1"));   // http 节点本身成功
        assertEquals("skipped", byId.get("llm_ok"));
        // http 节点 outputs 落库如实
        var httpRun = resp.nodeRuns().stream().filter(n -> "http_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(500, ((Number) httpRun.outputs().get("status")).intValue());
    }
```

- [ ] **Step 2: 跑集成测试**

```bash
cd server && mvn -Dtest=WorkflowRunFlowTest test
```
Expected: 两条新用例直接绿（端到端验证网）；W1/W2/W3a 既有 7 条不回归。退出码 0。

- [ ] **Step 3: 全量回归**

```bash
cd server && mvn verify
```
Expected: 退出码 0（含 ModularityTests/ArchUnit——workflow→infra 为 OPEN 模块合法依赖）。

- [ ] **Step 4: Commit**

```bash
git add server/src/test/java/com/hify/workflow/WorkflowRunFlowTest.java
git commit -m "test(workflow): W3b HTTP 节点分流全链路集成测试（200/500 两方向）"
```

---

### Task 6: deployment.md 补拍板 + Postman 验收集合 + 自检入档

**Files:**
- Modify: `docs/architecture/deployment.md`（§5 SSRF 条目末尾）
- Create: `docs/postman/workflow-w3b.postman_collection.json`
- Modify: `docs/self-check.md`（追加 W3b 一节）

- [ ] **Step 1: deployment.md §5 SSRF 条目末尾追加**

```markdown
  一期拍板（2026-07-11，W3b spec）：3xx 一律**不跟随**（status+Location 原样返回节点输出，
  彻底封死重定向绕过）；内网白名单**暂缓**（一期只调公网，机制预留：SsrfValidator 查
  system_setting 放行）；不做 DNS pinning（校验与连接间的 rebinding 窗口以一期威胁模型
  评估可接受，二期对外开放时收紧）。
```

- [ ] **Step 2: 写 Postman 集合**

以 `docs/postman/workflow-w3a.postman_collection.json` 为骨架复制（保留登录/查模型/建应用与变量捕获脚本），改名 `Hify · Workflow W3b HTTP 节点`。**集合描述第一条照旧**：`【验收前必须重启 hify-server——旧进程不会加载新代码】`。**关键设计：草稿的 url 写成 `{{start.targetUrl}}` 模板，一份草稿测全部路径（触发输入换 URL 即可）**。请求列表：

1. `0. 登录` / `1. 查可用 chat 模型` / `2. 创建 workflow 应用`——同 W3a
2. `3. 保存 HTTP 分流草稿`——PUT draft，body：

```json
{
  "graph": {
    "nodes": [
      {"id": "start", "type": "start", "data": {"inputs": [{"name": "targetUrl", "required": true}]}},
      {"id": "http_1", "type": "http", "data": {"method": "GET", "url": "{{start.targetUrl}}", "headers": {"Authorization": "Bearer test-token-for-masking"}}},
      {"id": "if_1", "type": "condition", "data": {"left": "{{http_1.status}}", "operator": "==", "right": "200"}},
      {"id": "llm_ok", "type": "llm", "data": {"modelId": "{{modelId}}", "userPrompt": "用一句话总结这段 JSON 的内容：{{http_1.body}}"}},
      {"id": "llm_fb", "type": "llm", "data": {"modelId": "{{modelId}}", "userPrompt": "接口返回了异常状态码 {{http_1.status}}，请礼貌地告知用户稍后再试"}},
      {"id": "end", "type": "end", "data": {"outputs": [{"name": "answer", "value": "{{llm_ok.text}}{{llm_fb.text}}"}]}}
    ],
    "edges": [
      {"source": "start", "target": "http_1"},
      {"source": "http_1", "target": "if_1"},
      {"source": "if_1", "target": "llm_ok", "sourceHandle": "true"},
      {"source": "if_1", "target": "llm_fb", "sourceHandle": "false"},
      {"source": "llm_ok", "target": "end"},
      {"source": "llm_fb", "target": "end"}
    ]
  }
}
```

3. `4. 触发·公网成功`——body `{"inputs": {"targetUrl": "https://httpbin.org/json"}}`；预期 succeeded、answer 来自 llm_ok、llm_fb=skipped
4. `5. 触发·404 分流`——body `{"inputs": {"targetUrl": "https://httpbin.org/status/404"}}`；预期 succeeded、answer 是致歉话术、http_1 节点 succeeded 且 outputs.status=404、llm_ok=skipped
5. `6. 触发·SSRF 拦截`——body `{"inputs": {"targetUrl": "http://127.0.0.1:8080/actuator/health"}}`；预期 HTTP 200 但 run=failed、errorMessage 含「禁止访问」、http_1 的 node_run.inputs 里 Authorization 为 `***`
6. `7. 运行详情查脱敏`——GET `/api/v1/workflow/runs/{{runId}}`；核对请求 4 的 http_1 inputs 中 Authorization=`***`

集合说明另注明：httpbin.org 需外网可达，不可达时换任何公网 JSON 接口。

- [ ] **Step 3: 校验 JSON 合法**

```bash
python3 -m json.tool docs/postman/workflow-w3b.postman_collection.json > /dev/null && echo OK
```
Expected: `OK`

- [ ] **Step 4: self-check 入档**

按 `docs/self-check.md` 既有格式追加 W3b 一节：范围（infra/outbound 地基 + http 节点）、四个拍板决策（非 2xx 不失败 / 不跟随重定向 / 白名单推迟 / JDK HttpClient）、DNS pinning 留账、测试数据（各层条数、`mvn verify` 退出码）、DoD 待办（Postman 四条路径，先重启服务）。

- [ ] **Step 5: Commit**

```bash
git add docs/architecture/deployment.md docs/postman/workflow-w3b.postman_collection.json docs/self-check.md
git commit -m "docs: W3b 验收集合与 deployment.md SSRF 拍板入档（不跟随重定向/白名单暂缓）"
```

---

## 验收 DoD（用户手动，反做假）

**先重启 hify-server**，Postman 跑 W3b 集合（同一份草稿，换触发 URL 走四条路径）：
1. 公网成功：httpbin.org/json → succeeded，回答总结了接口内容
2. 404 分流：succeeded，http_1 成功且 status=404，走致歉路
3. SSRF 拦截：127.0.0.1 → HTTP 200 + run failed + 拦截原因可见
4. 脱敏：node_run.inputs 里 Authorization 为 `***`

全部通过后由用户确认，再走 finishing-a-development-branch 收尾。
