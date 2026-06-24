# Provider 模型提供商管理后端（第 1 轮）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Admin 模型供应商（model_provider）的增删改查、启用/禁用与 API Key 加密存储，让前端 ProviderList 能从 mock 切到真后端。

**Architecture:** 按协议建模——一张 `model_provider` 表存 `protocol`(openai/anthropic) + `base_url` + 加密 key；鉴权差异由 protocol 在代码侧推导，不落多态结构。分层照 `demo`/`identity` 既有样板：`controller → service → mapper → entity`，DTO 不依赖 entity，投影在 service 私有方法。API Key 用 AES-256-GCM 对称加密（主密钥走 `.env`），本轮业务只调 `encrypt`。

**Tech Stack:** Spring Boot 3.5 + MyBatis-Plus + PostgreSQL(Flyway) + JCE(AES/GCM) + JUnit5/Mockito + Spring Security(Web 层测试)。

## Global Constraints

- 路由：`/api/v1/admin/provider/providers/**`；admin 权限由 `SecurityConfig` 的 `hasRole("ADMIN")` 统一拦截 `/api/v1/admin/**`，Controller 不加权限注解。
- 错误码全部复用 `com.hify.common.exception.CommonError`（不存在→`NOT_FOUND` 10005/404；重名→`CONFLICT` 10006/409；校验→`PARAM_INVALID` 10001/400）；**本轮不新增 12xxx**。
- 一期不用 PATCH；启停用动作子资源 `POST .../{id}/enable`、`/disable`。
- Long 主键由 infra Jackson 全局序列化为 **string**；时间用 `OffsetDateTime`（ISO-8601 带时区）。
- 实体继承 `com.hify.common.BaseEntity`（id/deleted/createTime/updateTime 四列自动填充）；DB 枚举用 `text + check`，布尔用 `boolean`，时间用 `timestamptz`，主键 `bigint generated always as identity`。
- DTO 包禁止 import entity 包（ArchUnit）；entity→DTO 投影写在 service 私有方法，不写 `Response.from(entity)`。
- service 是具体类 + `@Service`，不写接口/不拆 impl；`@Transactional` 只出现在 service 写方法；Controller 不注入 Mapper、不写 try-catch、不写事务。
- 配置外化：加密主密钥走 `application.yml` 引用 `.env` 的 `HIFY_PROVIDER_MASTER_KEY`，代码不硬编码。
- 测试：mock `ModelProviderMapper`（不连库，Testcontainers 推迟到 knowledge 轮）；TDD 先写失败用例；频繁提交。
- 每完成一个 Task，向 `docs/self-check.md` 追加一段自检（项目既有节奏）后再提交。
- 基准命令在 `server/` 目录执行：`cd /home/wang/playlab/hify/server`。

---

### Task 1: ApiKeyCipher（AES-256-GCM 加解密 + 配置）

**Files:**
- Create: `server/src/main/java/com/hify/provider/config/ProviderCryptoProperties.java`
- Create: `server/src/main/java/com/hify/provider/config/ProviderConfig.java`
- Create: `server/src/main/java/com/hify/provider/service/ApiKeyCipher.java`
- Test: `server/src/test/java/com/hify/provider/service/ApiKeyCipherTest.java`
- Modify: `server/src/main/resources/application.yml`（`hify:` 下新增 `provider.crypto.master-key`）
- Modify: `deploy/.env.example`（新增 `HIFY_PROVIDER_MASTER_KEY`）

**Interfaces:**
- Consumes: 无（叶子工具）。
- Produces:
  - `ApiKeyCipher.encrypt(String plaintext) -> String`（返回 `base64(IV‖密文‖tag)`）
  - `ApiKeyCipher.decrypt(String encoded) -> String`
  - `ProviderCryptoProperties.getMasterKey() -> String`

- [ ] **Step 1: 写失败测试**

`server/src/test/java/com/hify/provider/service/ApiKeyCipherTest.java`
```java
package com.hify.provider.service;

import com.hify.provider.config.ProviderCryptoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** ApiKeyCipher 单元测试：AES-256-GCM 往返一致、随机 IV、换密钥无法解密。不连库不依赖 Spring。 */
class ApiKeyCipherTest {

    private ApiKeyCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = new ApiKeyCipher(props("unit-test-master-key-any-string"));
    }

    private ProviderCryptoProperties props(String masterKey) {
        ProviderCryptoProperties p = new ProviderCryptoProperties();
        p.setMasterKey(masterKey);
        return p;
    }

    @Test
    void 加密后能原样解密回来() {
        String plain = "sk-1234567890abcdef";
        String enc = cipher.encrypt(plain);
        assertNotEquals(plain, enc);               // 密文不是明文
        assertEquals(plain, cipher.decrypt(enc));  // 往返一致
    }

    @Test
    void 同一明文两次加密密文不同_因随机IV() {
        assertNotEquals(cipher.encrypt("sk-same-input"), cipher.encrypt("sk-same-input"));
    }

    @Test
    void 换主密钥无法解密() {
        String enc = cipher.encrypt("sk-secret");
        ApiKeyCipher other = new ApiKeyCipher(props("a-completely-different-key"));
        assertThrows(Exception.class, () -> other.decrypt(enc)); // GCM tag 校验失败
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd /home/wang/playlab/hify/server && mvn -q -Dtest=ApiKeyCipherTest test`
Expected: 编译失败（`ApiKeyCipher` / `ProviderCryptoProperties` 不存在）。

- [ ] **Step 3: 实现配置类与加密器**

`server/src/main/java/com/hify/provider/config/ProviderCryptoProperties.java`
```java
package com.hify.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 供应商加密配置（外化到 application.yml 的 {@code hify.provider.crypto.*}）。
 * 主密钥这类敏感项走 {@code .env} 的 HIFY_PROVIDER_MASTER_KEY 引用，不在代码/yml 写明文。
 * 由 {@link ProviderConfig} 上的 {@code @EnableConfigurationProperties} 注册绑定。
 */
@ConfigurationProperties(prefix = "hify.provider.crypto")
public class ProviderCryptoProperties {

    /** 加密主密钥（任意非空字符串）；ApiKeyCipher 用 SHA-256 派生为 32 字节 AES-256 密钥。 */
    private String masterKey;

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }
}
```

`server/src/main/java/com/hify/provider/config/ProviderConfig.java`
```java
package com.hify.provider.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** provider 模块的本模块配置：注册 ConfigurationProperties 绑定。 */
@Configuration
@EnableConfigurationProperties(ProviderCryptoProperties.class)
public class ProviderConfig {
}
```

`server/src/main/java/com/hify/provider/service/ApiKeyCipher.java`
```java
package com.hify.provider.service;

import com.hify.provider.config.ProviderCryptoProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 供应商 API Key 的对称加解密（AES-256-GCM）。主密钥来自 {@link ProviderCryptoProperties}
 * （经 .env 注入），用 SHA-256 派生出固定 32 字节 AES-256 密钥——无需主密钥本身恰好 32 字节。
 *
 * <p>密文格式 {@code base64(IV ‖ ciphertext ‖ GCM tag)}，每次加密随机 12 字节 IV。
 *
 * <p>本轮业务流程<b>只调 {@link #encrypt}</b>；{@link #decrypt} 已实现且有测试覆盖，但无任何
 * endpoint/service 流程触达（留到 C 轮 ResilientChatModel 调真实模型时接入），解密路径不可经接口到达。
 */
@Component
public class ApiKeyCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;        // GCM 推荐 12 字节
    private static final int TAG_LENGTH_BITS = 128; // GCM 认证标签 128 位

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyCipher(ProviderCryptoProperties properties) {
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
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    /** {@code base64(IV ‖ 密文 ‖ tag)} → 明文。本轮无业务调用方。 */
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
            throw new IllegalStateException("API Key 解密失败", e);
        }
    }

    /** SHA-256(masterKey) → 32 字节 → AES-256 密钥。 */
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

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd /home/wang/playlab/hify/server && mvn -q -Dtest=ApiKeyCipherTest test`
Expected: BUILD SUCCESS（3 个用例全绿）。注意 `-q` 会静音输出，以退出码为准（`echo $?` 为 0）。

- [ ] **Step 5: 接入配置与 .env 样例**

在 `server/src/main/resources/application.yml` 的顶层 `hify:` 节点下（与既有 `hify.api`、`hify.security` 同级）新增：
```yaml
  # 供应商 API Key 加密（AES-256-GCM，经 SHA-256 派生密钥）。主密钥走 .env，生产务必覆盖。
  provider:
    crypto:
      master-key: ${HIFY_PROVIDER_MASTER_KEY:dev-only-hify-provider-master-key-please-override}
```

在 `deploy/.env.example` 末尾新增：
```bash
# --- 供应商 API Key 加密主密钥（AES-256-GCM；生产务必改成随机长串）---
# 生成示例：openssl rand -base64 48
HIFY_PROVIDER_MASTER_KEY=dev-only-hify-provider-master-key-please-override
```

- [ ] **Step 6: 追加自检并提交**

向 `docs/self-check.md` 追加一段：Task 1 ApiKeyCipher 完成，AES-256-GCM 往返/随机 IV/换密钥校验三测通过，配置外化到 .env。
```bash
cd /home/wang/playlab/hify
git add server/src/main/java/com/hify/provider/config server/src/main/java/com/hify/provider/service/ApiKeyCipher.java \
        server/src/test/java/com/hify/provider/service/ApiKeyCipherTest.java \
        server/src/main/resources/application.yml deploy/.env.example docs/self-check.md
git commit -m "feat(provider)：API Key AES-256-GCM 加密器 + 配置外化"
```

---

### Task 2: 数据层（V5 迁移 + 枚举 + 实体 + Mapper）

**Files:**
- Create: `server/src/main/resources/db/migration/V5__create_model_provider.sql`
- Create: `server/src/main/java/com/hify/provider/constant/ProviderStatus.java`
- Create: `server/src/main/java/com/hify/provider/entity/ModelProvider.java`
- Create: `server/src/main/java/com/hify/provider/mapper/ModelProviderMapper.java`

**Interfaces:**
- Consumes: `com.hify.common.BaseEntity`。
- Produces:
  - 实体 `ModelProvider`：字段 `name/protocol/baseUrl/apiKeyCipher/apiKeyTail/status`（String）+ BaseEntity 四列。
  - `ModelProviderMapper extends BaseMapper<ModelProvider>`。
  - `ProviderStatus.ENABLED/DISABLED`，有 `value()` 返回小写字符串。（protocol 合法值本轮由 DB check + DTO @Pattern 约束，不建枚举——留 C 轮。）

- [ ] **Step 1: 写 Flyway 迁移**

`server/src/main/resources/db/migration/V5__create_model_provider.sql`
```sql
-- V5：模型供应商表（provider 模块）。建表模板见 database-standards.md / V4：
-- 公共四列由 BaseEntity 承载；文本与枚举用 text + check，布尔用 boolean，时间用 timestamptz。
-- 本轮只做 Provider CRUD + Key 加密；韧性字段（max_concurrency 等）留后续轮次增量迁移。

create table model_provider (
    id             bigint      generated always as identity primary key,
    name           text        not null check (char_length(name) <= 50),
    protocol       text        not null check (protocol in ('openai', 'anthropic')),
    base_url       text        not null,
    api_key_cipher text        not null,
    api_key_tail   text        not null check (char_length(api_key_tail) <= 8),
    status         text        not null default 'enabled' check (status in ('enabled', 'disabled')),
    deleted        boolean     not null default false,
    create_time    timestamptz not null default now(),
    update_time    timestamptz not null default now()
);
comment on table model_provider is '模型供应商实例（provider 模块）：protocol 区分 OpenAI 兼容/Anthropic 两协议；api_key 加密存储';

-- 未软删的 name 唯一（配合 @TableLogic：软删后允许同名重建）。对齐 V4 sys_user 写法。
create unique index model_provider_name_uq on model_provider (name) where deleted = false;
```

- [ ] **Step 2: 写状态枚举**

`server/src/main/java/com/hify/provider/constant/ProviderStatus.java`
```java
package com.hify.provider.constant;

/** 供应商管理态。存库为小写字符串（与 model_provider.status 的 check 约束一致），镜像 UserStatus。 */
public enum ProviderStatus {

    ENABLED("enabled"),
    DISABLED("disabled");

    private final String value;

    ProviderStatus(String value) {
        this.value = value;
    }

    /** 入库/比较用的字符串值。 */
    public String value() {
        return value;
    }
}
```

- [ ] **Step 3: 写实体与 Mapper**

`server/src/main/java/com/hify/provider/entity/ModelProvider.java`
```java
package com.hify.provider.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 模型供应商表 {@code model_provider} 的映射实体。继承 {@link BaseEntity}，自动带
 * id / create_time / update_time / deleted 四列（填充逻辑在 infra 的 MetaObjectHandler）。
 * protocol / status 存小写字符串（见 ProviderProtocol / ProviderStatus 的 check 约束）。
 */
@TableName("model_provider")
public class ModelProvider extends BaseEntity {

    private String name;
    private String protocol;     // openai / anthropic
    private String baseUrl;
    private String apiKeyCipher; // AES-256-GCM 密文
    private String apiKeyTail;   // 明文后 4 位，仅供掩码展示
    private String status;       // enabled / disabled

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKeyCipher() {
        return apiKeyCipher;
    }

    public void setApiKeyCipher(String apiKeyCipher) {
        this.apiKeyCipher = apiKeyCipher;
    }

    public String getApiKeyTail() {
        return apiKeyTail;
    }

    public void setApiKeyTail(String apiKeyTail) {
        this.apiKeyTail = apiKeyTail;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
```

`server/src/main/java/com/hify/provider/mapper/ModelProviderMapper.java`
```java
package com.hify.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.provider.entity.ModelProvider;

/**
 * {@link ModelProvider} 的数据访问接口。继承 {@code BaseMapper} 即获得增删改查能力，无需写实现。
 * 由 {@code @MapperScan("com.hify.**.mapper")} 自动扫描注册，只允许被本模块 service 注入。
 */
public interface ModelProviderMapper extends BaseMapper<ModelProvider> {
}
```

- [ ] **Step 4: 编译并确认边界测试通过**

Run: `cd /home/wang/playlab/hify/server && mvn -q -Dtest=ModularityTests test`
Expected: BUILD SUCCESS（provider 新增包不破坏 Spring Modulith 模块边界；新代码无跨模块依赖）。
若该测试类名不同，改跑全量编译：`mvn -q test-compile` 应成功。

- [ ] **Step 5: 追加自检并提交**

向 `docs/self-check.md` 追加：Task 2 数据层完成，V5 建表（text+check / boolean / 部分唯一索引）、两枚举、实体、Mapper 就位，模块边界测试通过。
```bash
cd /home/wang/playlab/hify
git add server/src/main/resources/db/migration/V5__create_model_provider.sql \
        server/src/main/java/com/hify/provider/constant \
        server/src/main/java/com/hify/provider/entity/ModelProvider.java \
        server/src/main/java/com/hify/provider/mapper/ModelProviderMapper.java docs/self-check.md
git commit -m "feat(provider)：model_provider 建表迁移 + 实体/枚举/Mapper"
```

---

### Task 3: DTO + ProviderService（业务逻辑，TDD）

**Files:**
- Create: `server/src/main/java/com/hify/provider/dto/CreateProviderRequest.java`
- Create: `server/src/main/java/com/hify/provider/dto/UpdateProviderRequest.java`
- Create: `server/src/main/java/com/hify/provider/dto/ProviderResponse.java`
- Create: `server/src/main/java/com/hify/provider/service/ProviderService.java`
- Test: `server/src/test/java/com/hify/provider/service/ProviderServiceTest.java`

**Interfaces:**
- Consumes: `ModelProviderMapper`、`ApiKeyCipher`（Task 1/2）、`CommonError`、`ProviderStatus`。
- Produces（供 Task 4 Controller 调用）：
  - `ProviderService.create(CreateProviderRequest) -> ProviderResponse`
  - `ProviderService.update(Long id, UpdateProviderRequest) -> ProviderResponse`
  - `ProviderService.list() -> List<ProviderResponse>`
  - `ProviderService.delete(Long id) -> void`
  - `ProviderService.enable(Long id) -> void`
  - `ProviderService.disable(Long id) -> void`
  - `CreateProviderRequest(name, protocol, baseUrl, apiKey)`、`UpdateProviderRequest(name, protocol, baseUrl, apiKey)`、`ProviderResponse(id, name, protocol, baseUrl, status, apiKeyTail, createTime)`

- [ ] **Step 1: 写 DTO（先放，供测试编译）**

`server/src/main/java/com/hify/provider/dto/CreateProviderRequest.java`
```java
package com.hify.provider.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建供应商请求。校验只在此 DTO（api-standards 第 4 节）：名称 ≤50（对齐 DB check）、
 * protocol 限 openai|anthropic、baseUrl 须 http/https 前缀、apiKey 创建必填。
 */
public record CreateProviderRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Pattern(regexp = "openai|anthropic", message = "protocol 仅支持 openai|anthropic") String protocol,
        @NotBlank @Pattern(regexp = "^https?://.+", message = "baseUrl 必须以 http:// 或 https:// 开头") String baseUrl,
        @NotBlank String apiKey) {
}
```

`server/src/main/java/com/hify/provider/dto/UpdateProviderRequest.java`
```java
package com.hify.provider.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 全量更新供应商请求。name/protocol/baseUrl 严格全量必填；
 * apiKey 是「只写密钥」对 PUT 全量规则的<b>明确例外</b>（api-standards 第 2 节）：
 * 服务端无法把明文回传给前端整体提交，故留空（null 或空白）= 保留原密文不改，非空 = 重新加密覆盖。
 * 因此 apiKey 不加 @NotBlank。
 */
public record UpdateProviderRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Pattern(regexp = "openai|anthropic", message = "protocol 仅支持 openai|anthropic") String protocol,
        @NotBlank @Pattern(regexp = "^https?://.+", message = "baseUrl 必须以 http:// 或 https:// 开头") String baseUrl,
        String apiKey) {
}
```

`server/src/main/java/com/hify/provider/dto/ProviderResponse.java`
```java
package com.hify.provider.dto;

import java.time.OffsetDateTime;

/**
 * 供应商出参。刻意<b>不含</b>明文/密文 key——只回 {@code apiKeyTail}（明文后 4 位）供前端掩码展示。
 * id 经 infra Jackson 全局序列化为字符串；createTime 为 ISO-8601 带时区。
 * 本 record 不依赖 entity（ArchUnit 禁 DTO import entity），「实体→视图」投影在 ProviderService 完成。
 */
public record ProviderResponse(
        Long id,
        String name,
        String protocol,
        String baseUrl,
        String status,
        String apiKeyTail,
        OffsetDateTime createTime) {
}
```

- [ ] **Step 2: 写失败测试**

`server/src/test/java/com/hify/provider/service/ProviderServiceTest.java`
```java
package com.hify.provider.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.config.ProviderCryptoProperties;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.dto.CreateProviderRequest;
import com.hify.provider.dto.ProviderResponse;
import com.hify.provider.dto.UpdateProviderRequest;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.ModelProviderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProviderService 单元测试：mock ModelProviderMapper，用真实 ApiKeyCipher，不连库。
 * 覆盖创建/更新（含 apiKey 留空保留）/列表投影/启停幂等/删除幂等/重名与不存在分支。
 */
class ProviderServiceTest {

    private ModelProviderMapper mapper;
    private ApiKeyCipher cipher;
    private ProviderService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ModelProviderMapper.class);
        ProviderCryptoProperties props = new ProviderCryptoProperties();
        props.setMasterKey("unit-test-master-key");
        cipher = new ApiKeyCipher(props);
        service = new ProviderService(mapper, cipher);
    }

    private CreateProviderRequest createReq() {
        return new CreateProviderRequest(
                "通义-生产", "openai",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-abcdef123456");
    }

    private ModelProvider stored(long id, String name, String status) {
        ModelProvider e = new ModelProvider();
        e.setId(id);
        e.setName(name);
        e.setProtocol("openai");
        e.setBaseUrl("https://api.openai.com/v1");
        e.setApiKeyCipher("OLD-CIPHER");
        e.setApiKeyTail("3456");
        e.setStatus(status);
        e.setCreateTime(OffsetDateTime.now());
        return e;
    }

    @Test
    void 创建_密钥被加密_写后4位tail_状态默认启用() {
        when(mapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<ModelProvider> captor = ArgumentCaptor.forClass(ModelProvider.class);

        ProviderResponse resp = service.create(createReq());

        verify(mapper).insert(captor.capture());
        ModelProvider saved = captor.getValue();
        assertNotEquals("sk-abcdef123456", saved.getApiKeyCipher());            // 不是明文
        assertEquals("sk-abcdef123456", cipher.decrypt(saved.getApiKeyCipher())); // 可解回
        assertEquals("3456", saved.getApiKeyTail());                            // 后 4 位
        assertEquals(ProviderStatus.ENABLED.value(), saved.getStatus());
        assertEquals("3456", resp.apiKeyTail());
    }

    @Test
    void 创建_重名预检_抛CONFLICT() {
        when(mapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class, () -> service.create(createReq()));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 创建_并发命中唯一索引_转CONFLICT() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(ModelProvider.class))).thenThrow(new DuplicateKeyException("dup"));

        BizException ex = assertThrows(BizException.class, () -> service.create(createReq()));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 更新_apiKey留空_保留原密文与tail_其余字段更新() {
        ModelProvider existing = stored(5L, "old-name", ProviderStatus.ENABLED.value());
        when(mapper.selectById(5L)).thenReturn(existing);
        when(mapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<ModelProvider> captor = ArgumentCaptor.forClass(ModelProvider.class);

        service.update(5L, new UpdateProviderRequest(
                "new-name", "anthropic", "https://api.anthropic.com", ""));

        verify(mapper).updateById(captor.capture());
        ModelProvider saved = captor.getValue();
        assertEquals("OLD-CIPHER", saved.getApiKeyCipher()); // 密文未动
        assertEquals("3456", saved.getApiKeyTail());          // tail 未动
        assertEquals("new-name", saved.getName());            // 其余字段更新
        assertEquals("anthropic", saved.getProtocol());
    }

    @Test
    void 更新_apiKey非空_重新加密并刷新tail() {
        ModelProvider existing = stored(5L, "x", ProviderStatus.ENABLED.value());
        when(mapper.selectById(5L)).thenReturn(existing);
        when(mapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<ModelProvider> captor = ArgumentCaptor.forClass(ModelProvider.class);

        service.update(5L, new UpdateProviderRequest(
                "x", "openai", "https://api.openai.com/v1", "sk-newkey9999"));

        verify(mapper).updateById(captor.capture());
        ModelProvider saved = captor.getValue();
        assertEquals("sk-newkey9999", cipher.decrypt(saved.getApiKeyCipher()));
        assertEquals("9999", saved.getApiKeyTail());
    }

    @Test
    void 更新_不存在_抛NOT_FOUND() {
        when(mapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.update(99L,
                new UpdateProviderRequest("x", "openai", "https://api.openai.com/v1", "")));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 列表_投影出tail与status_不暴露密文字段() {
        when(mapper.selectList(any())).thenReturn(List.of(
                stored(1L, "a", ProviderStatus.ENABLED.value()),
                stored(2L, "b", ProviderStatus.DISABLED.value())));

        List<ProviderResponse> list = service.list();

        assertEquals(2, list.size());
        assertEquals("3456", list.get(0).apiKeyTail());
        assertEquals(ProviderStatus.DISABLED.value(), list.get(1).status());
        // ProviderResponse 无 cipher/apiKey 字段，密文不可能出响应（编译期保证）
    }

    @Test
    void 启用_已启用_幂等不写库() {
        when(mapper.selectById(1L)).thenReturn(stored(1L, "a", ProviderStatus.ENABLED.value()));

        service.enable(1L);

        verify(mapper, never()).updateById(any(ModelProvider.class));
    }

    @Test
    void 禁用_启用态_写库为disabled() {
        when(mapper.selectById(1L)).thenReturn(stored(1L, "a", ProviderStatus.ENABLED.value()));
        ArgumentCaptor<ModelProvider> captor = ArgumentCaptor.forClass(ModelProvider.class);

        service.disable(1L);

        verify(mapper).updateById(captor.capture());
        assertEquals(ProviderStatus.DISABLED.value(), captor.getValue().getStatus());
    }

    @Test
    void 启停_不存在_抛NOT_FOUND() {
        when(mapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.enable(99L));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 删除_走软删() {
        service.delete(5L);
        verify(mapper).deleteById(5L);
    }

    @Test
    void 删除_不存在_幂等不抛() {
        service.delete(99L); // 不抛即通过（deleteById 对已不存在的也算成功）
        verify(mapper).deleteById(99L);
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `cd /home/wang/playlab/hify/server && mvn -q -Dtest=ProviderServiceTest test`
Expected: 编译失败（`ProviderService` 不存在）。

- [ ] **Step 4: 实现 ProviderService**

`server/src/main/java/com/hify/provider/service/ProviderService.java`
```java
package com.hify.provider.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.dto.CreateProviderRequest;
import com.hify.provider.dto.ProviderResponse;
import com.hify.provider.dto.UpdateProviderRequest;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.ModelProviderMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 模型供应商业务逻辑（具体类 + @Service，不拆接口——code-organization.md 第 2 节）。
 * 注入本模块 ModelProviderMapper 与 ApiKeyCipher；@Transactional 只在本层写方法；
 * 失败一律抛 BizException 交全局处理器转信封。Entity ↔ DTO 转换在本层（dto 包禁依赖 entity）。
 */
@Service
public class ProviderService {

    private final ModelProviderMapper providerMapper;
    private final ApiKeyCipher apiKeyCipher;

    public ProviderService(ModelProviderMapper providerMapper, ApiKeyCipher apiKeyCipher) {
        this.providerMapper = providerMapper;
        this.apiKeyCipher = apiKeyCipher;
    }

    @Transactional
    public ProviderResponse create(CreateProviderRequest request) {
        assertNameAvailable(request.name(), null);
        ModelProvider entity = new ModelProvider();
        entity.setName(request.name());
        entity.setProtocol(request.protocol());
        entity.setBaseUrl(request.baseUrl());
        entity.setApiKeyCipher(apiKeyCipher.encrypt(request.apiKey()));
        entity.setApiKeyTail(tailOf(request.apiKey()));
        entity.setStatus(ProviderStatus.ENABLED.value());
        try {
            providerMapper.insert(entity); // id 回填；create_time/update_time/deleted 自动填充
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "供应商名称已存在", e);
        }
        return toResponse(entity);
    }

    @Transactional
    public ProviderResponse update(Long id, UpdateProviderRequest request) {
        ModelProvider entity = require(id);
        assertNameAvailable(request.name(), id);
        entity.setName(request.name());
        entity.setProtocol(request.protocol());
        entity.setBaseUrl(request.baseUrl());
        // 只写密钥例外：留空保留原密文/tail；非空才重新加密并刷新 tail
        if (StringUtils.hasText(request.apiKey())) {
            entity.setApiKeyCipher(apiKeyCipher.encrypt(request.apiKey()));
            entity.setApiKeyTail(tailOf(request.apiKey()));
        }
        try {
            providerMapper.updateById(entity); // update_time 自动刷新
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "供应商名称已存在", e);
        }
        return toResponse(entity);
    }

    /** 列表：@TableLogic 自动加 where deleted=false，软删的不出现。按创建时间倒序。 */
    public List<ProviderResponse> list() {
        List<ModelProvider> rows = providerMapper.selectList(
                new LambdaQueryWrapper<ModelProvider>().orderByDesc(ModelProvider::getCreateTime));
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional
    public void enable(Long id) {
        ModelProvider entity = require(id);
        if (ProviderStatus.ENABLED.value().equals(entity.getStatus())) {
            return; // 幂等：已启用不写库
        }
        entity.setStatus(ProviderStatus.ENABLED.value());
        providerMapper.updateById(entity);
    }

    @Transactional
    public void disable(Long id) {
        ModelProvider entity = require(id);
        if (ProviderStatus.DISABLED.value().equals(entity.getStatus())) {
            return; // 幂等：已禁用不写库
        }
        entity.setStatus(ProviderStatus.DISABLED.value());
        providerMapper.updateById(entity);
    }

    /** 逻辑删除：@TableLogic 把 delete 变成 update set deleted=true；删不存在的也返回成功（幂等）。 */
    @Transactional
    public void delete(Long id) {
        providerMapper.deleteById(id);
    }

    private ModelProvider require(Long id) {
        ModelProvider entity = providerMapper.selectById(id);
        if (entity == null) {
            throw new BizException(CommonError.NOT_FOUND, "供应商不存在");
        }
        return entity;
    }

    /** 名称在未软删范围内唯一；excludeId 非 null 时排除自身（更新场景）。 */
    private void assertNameAvailable(String name, Long excludeId) {
        LambdaQueryWrapper<ModelProvider> q = new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getName, name);
        if (excludeId != null) {
            q.ne(ModelProvider::getId, excludeId);
        }
        if (providerMapper.selectCount(q) > 0) {
            throw new BizException(CommonError.CONFLICT, "供应商名称已存在");
        }
    }

    /** 取明文后 4 位作掩码尾巴；不足 4 位整体返回。 */
    private String tailOf(String apiKey) {
        return apiKey.length() <= 4 ? apiKey : apiKey.substring(apiKey.length() - 4);
    }

    /** 实体→视图投影：api_key_cipher / 明文 key 绝不进 DTO。放 service 层（dto 禁依赖 entity）。 */
    private ProviderResponse toResponse(ModelProvider e) {
        return new ProviderResponse(
                e.getId(), e.getName(), e.getProtocol(), e.getBaseUrl(),
                e.getStatus(), e.getApiKeyTail(), e.getCreateTime());
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `cd /home/wang/playlab/hify/server && mvn -q -Dtest=ProviderServiceTest test`
Expected: BUILD SUCCESS（12 个用例全绿；退出码 0）。

- [ ] **Step 6: 追加自检并提交**

向 `docs/self-check.md` 追加：Task 3 业务层完成，CRUD + 启停 + 重名/不存在/幂等 + apiKey 留空保留逻辑全测通过。
```bash
cd /home/wang/playlab/hify
git add server/src/main/java/com/hify/provider/dto server/src/main/java/com/hify/provider/service/ProviderService.java \
        server/src/test/java/com/hify/provider/service/ProviderServiceTest.java docs/self-check.md
git commit -m "feat(provider)：ProviderService CRUD + 启停 + DTO（含 apiKey 留空保留）"
```

---

### Task 4: AdminProviderController（REST 接口，Web 层测试）

**Files:**
- Create: `server/src/main/java/com/hify/provider/controller/AdminProviderController.java`
- Test: `server/src/test/java/com/hify/provider/controller/AdminProviderControllerTest.java`

**Interfaces:**
- Consumes: `ProviderService`（Task 3）的 6 个方法。
- Produces: 6 个 admin 端点（见 Global Constraints 路由）。

- [ ] **Step 1: 写失败测试**

`server/src/test/java/com/hify/provider/controller/AdminProviderControllerTest.java`
```java
package com.hify.provider.controller;

import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.provider.dto.ProviderResponse;
import com.hify.provider.service.ProviderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminProviderController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AdminProviderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ProviderService providerService;

    private String adminToken() {
        return jwtService.generateToken(new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN));
    }

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(2L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private ProviderResponse sample() {
        return new ProviderResponse(7L, "通义-生产", "openai",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "enabled", "3456", OffsetDateTime.now());
    }

    @Test
    void 列表_admin_200且id为字符串且无密文字段() throws Exception {
        when(providerService.list()).thenReturn(List.of(sample()));

        mockMvc.perform(get("/api/v1/admin/provider/providers")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value("7"))               // Long→字符串
                .andExpect(jsonPath("$.data[0].apiKeyTail").value("3456"))
                .andExpect(jsonPath("$.data[0].apiKeyCipher").doesNotExist()) // 不泄露密文
                .andExpect(jsonPath("$.data[0].apiKey").doesNotExist());
    }

    @Test
    void 创建_admin_200() throws Exception {
        when(providerService.create(any())).thenReturn(sample());

        mockMvc.perform(post("/api/v1/admin/provider/providers")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"name\":\"通义-生产\",\"protocol\":\"openai\","
                                + "\"baseUrl\":\"https://dashscope.aliyuncs.com/compatible-mode/v1\",\"apiKey\":\"sk-abcdef123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.protocol").value("openai"));
    }

    @Test
    void 更新_admin_200() throws Exception {
        when(providerService.update(eq(7L), any())).thenReturn(sample());

        mockMvc.perform(put("/api/v1/admin/provider/providers/7")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"name\":\"通义-生产\",\"protocol\":\"openai\","
                                + "\"baseUrl\":\"https://api.openai.com/v1\",\"apiKey\":\"\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 删除_admin_200且data不存在() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/provider/providers/7")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 启用_admin_200() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/providers/7/enable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 禁用_admin_200() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/providers/7/disable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 创建_member_403且10004() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/providers")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"protocol\":\"openai\",\"baseUrl\":\"https://a.com\",\"apiKey\":\"k\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10004));
    }

    @Test
    void 列表_无令牌_401且10002() throws Exception {
        mockMvc.perform(get("/api/v1/admin/provider/providers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void 创建_protocol非法_400且10001带字段数组() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/providers")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"protocol\":\"gemini\",\"baseUrl\":\"https://a.com\",\"apiKey\":\"k\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data").isArray());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd /home/wang/playlab/hify/server && mvn -q -Dtest=AdminProviderControllerTest test`
Expected: 编译失败（`AdminProviderController` 不存在）。

- [ ] **Step 3: 实现 Controller**

`server/src/main/java/com/hify/provider/controller/AdminProviderController.java`
```java
package com.hify.provider.controller;

import com.hify.common.Result;
import com.hify.provider.dto.CreateProviderRequest;
import com.hify.provider.dto.ProviderResponse;
import com.hify.provider.dto.UpdateProviderRequest;
import com.hify.provider.service.ProviderService;
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
 * admin 模型供应商管理接口（仅 Admin）。路径在 /api/v1/admin/** 下，由 SecurityConfig 的
 * hasRole("ADMIN") 统一拦截，类上无需再加注解。协议层：@Valid 校验 → 调本模块 service → 包 Result；
 * 无业务逻辑、无 try-catch、无 @Transactional、不注入 Mapper（code-organization.md 第 2 节）。
 * 启停用动作子资源 POST（不用 PATCH）；删除/启停成功返回 Void，前端重拉列表刷新。
 */
@RestController
@RequestMapping("/api/v1/admin/provider/providers")
public class AdminProviderController {

    private final ProviderService providerService;

    public AdminProviderController(ProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping
    public Result<List<ProviderResponse>> list() {
        return Result.ok(providerService.list());
    }

    @PostMapping
    public Result<ProviderResponse> create(@Valid @RequestBody CreateProviderRequest request) {
        return Result.ok(providerService.create(request));
    }

    @PutMapping("/{id}")
    public Result<ProviderResponse> update(@PathVariable Long id,
                                           @Valid @RequestBody UpdateProviderRequest request) {
        return Result.ok(providerService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        providerService.delete(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        providerService.enable(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        providerService.disable(id);
        return Result.ok(null);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd /home/wang/playlab/hify/server && mvn -q -Dtest=AdminProviderControllerTest test`
Expected: BUILD SUCCESS（9 个用例全绿；退出码 0）。

- [ ] **Step 5: 追加自检并提交**

向 `docs/self-check.md` 追加：Task 4 接口层完成，6 端点路由通、admin/member/无令牌鉴权与 @Valid 校验全测通过，响应无密文。
```bash
cd /home/wang/playlab/hify
git add server/src/main/java/com/hify/provider/controller/AdminProviderController.java \
        server/src/test/java/com/hify/provider/controller/AdminProviderControllerTest.java docs/self-check.md
git commit -m "feat(provider)：AdminProviderController 6 端点 + Web 层测试"
```

---

### Task 5: 全量回归 + 边界校验

**Files:**
- 无新增（验证收尾）。可能 Modify: 若 ArchUnit/Modularity 报违规，按提示在 provider 模块内修正。

**Interfaces:** 无。

- [ ] **Step 1: 跑全量测试**

Run: `cd /home/wang/playlab/hify/server && mvn -q test`
Expected: BUILD SUCCESS；退出码 0（`echo $?` 为 0）。涵盖既有 identity/demo 测试 + 本轮 provider 三个测试类 + ModularityTests/ArchUnit。

- [ ] **Step 2: 确认模块边界绿**

确认输出无 Spring Modulith / ArchUnit 违规（provider 仅依赖 common/infra，DTO 未 import entity）。若有违规，按报告定位到具体类修正后重跑 Step 1。

- [ ] **Step 3: 追加自检并提交（如有改动）**

向 `docs/self-check.md` 追加：Task 5 全量回归通过，模块边界无违规，Provider 后端第 1 轮完成。
```bash
cd /home/wang/playlab/hify
git add -A && git commit -m "test(provider)：全量回归通过，Provider 后端第1轮收尾" || echo "无改动，跳过提交"
```

---

## 完成标准（Definition of Done）

- `mvn test` 全绿；provider 三个测试类（Cipher/Service/Controller）共约 24 个用例通过。
- 6 个 admin 端点按 api-standards 返回 `Result` 信封；响应永不含明文/密文 key。
- `model_provider` 表经 V5 迁移建立；API Key 以 AES-256-GCM 密文入库，主密钥走 `.env`。
- 模块边界（Modulith/ArchUnit）无违规。

## 后续（不在本计划）

- 实现完成后按用户要求生成 Postman collection（6 端点 + 示例请求体 + admin 登录取 token 的前置请求）供手测。
- B/C/D 轮见 spec 第 7 节。
