# conversation 单轮聊天 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通 identity → app → provider → conversation，实现控制台内"试聊"：发一条消息，经应用绑定的模型一次性 `.call()` 拿回复，前后端一体。

**Architecture:** conversation 模块新建 `conversation` + `message` 两表（多轮就绪，本轮只跑单轮：组 prompt 只含 systemPrompt + 当前消息）。编排在 `ConversationService`（不带事务），LLM 调用夹在两个事务（`ConversationStore`）中间——满足"`@Transactional` 内禁外部 IO"。app 新增 `AppFacade.findRunnableChatApp` 供 conversation 读应用元数据；复用已有 `ProviderFacade.getChatClient`。前端极简单会话页，从应用列表"试聊"进入。

**Tech Stack:** Spring Boot 3 / Java 21 / MyBatis-Plus / Spring AI 1.0.1（`ChatClient`）/ Spring Modulith / Flyway / JUnit5 + Mockito；前端 Vue 3 `<script setup>` + TS + Element Plus + vitest。

## Global Constraints

- 写 Controller/DTO 前现场重读 `docs/architecture/api-standards.md`；admin 路由带模块段、一期不用 PATCH、Long 序列化为 string、错误码优先复用通用段（`CommonError`）。
- `@Transactional` 只允许在 `service/` 层；其内**禁止任何 LLM/外部 IO**（CLAUDE.md 硬规则 6 / llm-resilience.md §1）。
- 跨模块只能 import `com.hify.<other>.api..`；跨模块只存 id 不建外键；模块内可建 FK。
- DTO/Event 用 `record`；`api/` 与 `dto/` 禁 import `entity`（ArchUnit 守护）；entity 不出现在 controller/dto/api 签名。
- JSON：camelCase；Long→string；时间 ISO-8601 带时区；集合永不为 null（空即 `[]`）；字符串无值用 null 不用 `""`。
- conversation 错误码段 = **17xxx**；模型类失败由 provider 抛的 `12002/12003/12004` 透传，不重复发明。
- 会话是个人数据：成员族接口固定按当前用户过滤（`where user_id = 当前用户`），非本人/不存在一律 `404`。
- 测试：TDD；判 mvn 结果**不 grep `BUILD SUCCESS`**（看 Surefire `Tests run / Failures / Errors`）。前端 vitest，测试放 `__tests__/`。
- 后端从 `server/` 目录跑（无 mvnw，用 `mvn`）；前端从 `web/` 跑 `pnpm`。
- 分支：本轮在 `feat/conversation-single-turn`（已建，spec 已提交）。

---

### Task 1: AppFacade —— app 暴露"可对话应用"读契约

**Files:**
- Create: `server/src/main/java/com/hify/app/api/dto/AppRuntimeView.java`
- Create: `server/src/main/java/com/hify/app/api/AppFacade.java`
- Create: `server/src/main/java/com/hify/app/service/AppFacadeImpl.java`
- Test: `server/src/test/java/com/hify/app/service/AppFacadeImplTest.java`

**Interfaces:**
- Consumes: `App` 实体（`getType/getStatus/getModelId/getConfig`）、`AppType.CHAT`、`AppStatus.ENABLED`、`AppConfig`、`AppMapper.selectById`。
- Produces：
  - `record AppRuntimeView(Long appId, Long modelId, AppConfig config)`
  - `AppFacade.findRunnableChatApp(Long appId) : Optional<AppRuntimeView>` —— 应用不存在/非 chat/已停用/未绑定 modelId → `Optional.empty()`。

- [ ] **Step 1: 写失败测试**

`AppFacadeImplTest.java`：
```java
package com.hify.app.service;

import com.hify.app.api.dto.AppConfig;
import com.hify.app.api.dto.AppRuntimeView;
import com.hify.app.constant.AppStatus;
import com.hify.app.constant.AppType;
import com.hify.app.entity.App;
import com.hify.app.mapper.AppMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppFacadeImplTest {

    private AppMapper mapper;
    private AppFacadeImpl facade;

    @BeforeEach
    void setUp() {
        mapper = mock(AppMapper.class);
        facade = new AppFacadeImpl(mapper);
    }

    private App app(String type, String status, Long modelId) {
        App a = new App();
        a.setId(10L);
        a.setType(type);
        a.setStatus(status);
        a.setModelId(modelId);
        a.setConfig(new AppConfig("你是客服"));
        return a;
    }

    @Test
    void 可对话应用_返回视图含modelId与config() {
        when(mapper.selectById(eq(10L)))
                .thenReturn(app(AppType.CHAT.value(), AppStatus.ENABLED.value(), 5L));
        Optional<AppRuntimeView> v = facade.findRunnableChatApp(10L);
        assertTrue(v.isPresent());
        assertEquals(5L, v.get().modelId());
        assertEquals("你是客服", v.get().config().systemPrompt());
    }

    @Test
    void 应用不存在_空() {
        when(mapper.selectById(eq(10L))).thenReturn(null);
        assertTrue(facade.findRunnableChatApp(10L).isEmpty());
    }

    @Test
    void 已停用_空() {
        when(mapper.selectById(eq(10L)))
                .thenReturn(app(AppType.CHAT.value(), AppStatus.DISABLED.value(), 5L));
        assertTrue(facade.findRunnableChatApp(10L).isEmpty());
    }

    @Test
    void 非对话型_空() {
        when(mapper.selectById(eq(10L)))
                .thenReturn(app("workflow", AppStatus.ENABLED.value(), 5L));
        assertTrue(facade.findRunnableChatApp(10L).isEmpty());
    }

    @Test
    void 未绑定模型_空() {
        when(mapper.selectById(eq(10L)))
                .thenReturn(app(AppType.CHAT.value(), AppStatus.ENABLED.value(), null));
        assertTrue(facade.findRunnableChatApp(10L).isEmpty());
    }

    @Test
    void 入参null_空() {
        assertTrue(facade.findRunnableChatApp(null).isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && mvn -Dtest=AppFacadeImplTest test`
Expected: 编译失败（`AppFacade`/`AppRuntimeView`/`AppFacadeImpl` 不存在）。

- [ ] **Step 3: 写实现**

`AppRuntimeView.java`：
```java
package com.hify.app.api.dto;

/**
 * 应用运行时视图（跨模块）：conversation 取它发起对话。
 * modelId 必非空（findRunnableChatApp 已保证可运行才返回）；config 含 systemPrompt。
 */
public record AppRuntimeView(Long appId, Long modelId, AppConfig config) {
}
```

`AppFacade.java`：
```java
package com.hify.app.api;

import com.hify.app.api.dto.AppRuntimeView;

import java.util.Optional;

/**
 * app 模块对外门面。签名只用 api/dto + JDK 类型。
 */
public interface AppFacade {

    /**
     * 取一个「可发起对话」的应用视图。可运行 = 应用存在 + type=chat + status=enabled + 已绑定 modelId。
     * 任一不满足返回 {@link Optional#empty()}；调用方据空抛自身错误码（conversation 抛 17001）。
     * 注意：本方法不校验模型是否可用（供应商启停由 conversation 经 ProviderFacade.getChatClient 校验，抛 12002）。
     */
    Optional<AppRuntimeView> findRunnableChatApp(Long appId);
}
```

`AppFacadeImpl.java`：
```java
package com.hify.app.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.dto.AppConfig;
import com.hify.app.api.dto.AppRuntimeView;
import com.hify.app.constant.AppStatus;
import com.hify.app.constant.AppType;
import com.hify.app.entity.App;
import com.hify.app.mapper.AppMapper;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * {@link AppFacade} 实现：纯读 + 投影到 AppRuntimeView（不 import entity 的禁令只约束 api/dto，service 可碰 entity）。
 */
@Service
public class AppFacadeImpl implements AppFacade {

    private final AppMapper appMapper;

    public AppFacadeImpl(AppMapper appMapper) {
        this.appMapper = appMapper;
    }

    @Override
    public Optional<AppRuntimeView> findRunnableChatApp(Long appId) {
        if (appId == null) {
            return Optional.empty();
        }
        App app = appMapper.selectById(appId);
        if (app == null
                || !AppType.CHAT.value().equals(app.getType())
                || !AppStatus.ENABLED.value().equals(app.getStatus())
                || app.getModelId() == null) {
            return Optional.empty();
        }
        AppConfig config = app.getConfig() == null ? new AppConfig(null) : app.getConfig();
        return Optional.of(new AppRuntimeView(app.getId(), app.getModelId(), config));
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && mvn -Dtest=AppFacadeImplTest test`
Expected: `Tests run: 6, Failures: 0, Errors: 0`。

- [ ] **Step 5: 跑模块边界测试确认未破坏**

Run: `cd server && mvn -Dtest=ModularityTests,LayerRulesTest test`
Expected: 全绿（新 Facade 在 api、impl 在 service，符合白名单）。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/java/com/hify/app/api/ server/src/main/java/com/hify/app/service/AppFacadeImpl.java server/src/test/java/com/hify/app/service/AppFacadeImplTest.java
git commit -m "feat(app): AppFacade.findRunnableChatApp 暴露可对话应用读契约（供 conversation）"
```

---

### Task 2: 建表迁移 + 实体 + Mapper + 常量

**Files:**
- Create: `server/src/main/resources/db/migration/V10__create_conversation_message.sql`
- Create: `server/src/main/java/com/hify/conversation/entity/Conversation.java`
- Create: `server/src/main/java/com/hify/conversation/entity/Message.java`
- Create: `server/src/main/java/com/hify/conversation/mapper/ConversationMapper.java`
- Create: `server/src/main/java/com/hify/conversation/mapper/MessageMapper.java`
- Create: `server/src/main/java/com/hify/conversation/constant/MessageRole.java`
- Create: `server/src/main/java/com/hify/conversation/constant/ConversationError.java`
- Test: `server/src/test/java/com/hify/conversation/constant/ConversationEnumTest.java`
- Delete: 上述目录下对应 `.gitkeep`（被真实文件替代的可留可删；不强制）

**Interfaces:**
- Produces：
  - `Conversation extends BaseEntity { Long appId; Long userId; String title; }`
  - `Message extends BaseEntity { Long conversationId; String role; String content; Integer promptTokens; Integer completionTokens; }`（tool_calls 本轮不映射字段，DB 默认 `[]`）
  - `MessageRole.USER/ASSISTANT`，`value()` → `"user"`/`"assistant"`
  - `ConversationError.APP_NOT_RUNNABLE`（17001/400）
  - `ConversationMapper extends BaseMapper<Conversation>`、`MessageMapper extends BaseMapper<Message>`

- [ ] **Step 1: 写失败测试（常量/枚举值锁定，对齐 DB check）**

`ConversationEnumTest.java`：
```java
package com.hify.conversation.constant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversationEnumTest {

    @Test
    void 角色值与DB_check一致() {
        assertEquals("user", MessageRole.USER.value());
        assertEquals("assistant", MessageRole.ASSISTANT.value());
    }

    @Test
    void 错误码_APP_NOT_RUNNABLE_为17001_400() {
        assertEquals(17001, ConversationError.APP_NOT_RUNNABLE.code());
        assertEquals(HttpStatus.BAD_REQUEST, ConversationError.APP_NOT_RUNNABLE.status());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && mvn -Dtest=ConversationEnumTest test`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 写迁移脚本**

`V10__create_conversation_message.sql`：
```sql
-- V10：会话与消息表（conversation 模块）。单轮先行、按多轮就绪建表（data-model.md §1）。
-- 跨模块 app_id/user_id 只存 id、不建外键（§3 条1）；模块内 message.conversation_id 建 FK 享级联删。

create table conversation (
    id          bigint      generated always as identity primary key,
    app_id      bigint      not null,
    user_id     bigint      not null,
    title       text        check (char_length(title) <= 100),
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table conversation is '会话（conversation 模块）：归属某用户、绑定某应用；个人数据仅本人可见';
create index conversation_user_idx on conversation (user_id, update_time desc) where deleted = false;

create table message (
    id                bigint      generated always as identity primary key,
    conversation_id   bigint      not null references conversation (id) on delete cascade,
    role              text        not null check (role in ('user', 'assistant')),
    content           text        not null,
    prompt_tokens     integer,
    completion_tokens integer,
    tool_calls        jsonb       not null default '[]',
    deleted           boolean     not null default false,
    create_time       timestamptz not null default now(),
    update_time       timestamptz not null default now()
);
comment on table message is '消息（conversation 模块）：role 分 user/assistant；tool_calls 预留 Agent 轨迹，本轮恒空';
create index message_conversation_idx on message (conversation_id, id) where deleted = false;
```

- [ ] **Step 4: 写实体与 Mapper 与常量**

`MessageRole.java`（仿 `AppStatus` 的 `value()` 模式）：
```java
package com.hify.conversation.constant;

/** 消息角色，value() 与 DB check（'user'/'assistant'）一致。 */
public enum MessageRole {

    USER("user"),
    ASSISTANT("assistant");

    private final String value;

    MessageRole(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
```

`ConversationError.java`（仿 `AppError`）：
```java
package com.hify.conversation.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * conversation 模块特有错误码（17xxx 段）。通用语义（不存在/权限/校验）复用 CommonError；
 * 模型不可用/熔断/繁忙由 provider 的 12002/12003/12004 透传，不在此重复。
 */
public enum ConversationError implements ErrorCode {

    /** 应用不存在/非对话型/已停用/未绑定模型——无法发起对话。 */
    APP_NOT_RUNNABLE(17001, HttpStatus.BAD_REQUEST, "应用未绑定可用模型或已停用，无法发起对话");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    ConversationError(int code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
```

`Conversation.java`：
```java
package com.hify.conversation.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 会话表 {@code conversation} 映射实体。继承 BaseEntity（id/createTime/updateTime/deleted）。
 * app_id/user_id 跨模块弱引用（只存 id，不建外键）。
 */
@TableName("conversation")
public class Conversation extends BaseEntity {

    private Long appId;
    private Long userId;
    private String title;

    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}
```

`Message.java`：
```java
package com.hify.conversation.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 消息表 {@code message} 映射实体。role 存 'user'/'assistant'（见 MessageRole）。
 * tool_calls(jsonb) 本轮恒空、不映射字段（DB 默认 '[]'），留待 Agent 轨迹。
 */
@TableName("message")
public class Message extends BaseEntity {

    private Long conversationId;
    private String role;
    private String content;
    private Integer promptTokens;
    private Integer completionTokens;

    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
}
```

`ConversationMapper.java`：
```java
package com.hify.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.conversation.entity.Conversation;

public interface ConversationMapper extends BaseMapper<Conversation> {
}
```

`MessageMapper.java`：
```java
package com.hify.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.conversation.entity.Message;

public interface MessageMapper extends BaseMapper<Message> {
}
```

- [ ] **Step 5: 跑测试确认通过 + 编译全量 + 模块边界**

Run: `cd server && mvn -Dtest=ConversationEnumTest test`
Expected: `Tests run: 2, Failures: 0`。

Run: `cd server && mvn -Dtest=ModularityTests,LayerRulesTest test`
Expected: 全绿（entity/mapper 各就各位；`message.conversation_id` 模块内 FK 合规）。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/resources/db/migration/V10__create_conversation_message.sql \
        server/src/main/java/com/hify/conversation/entity/ \
        server/src/main/java/com/hify/conversation/mapper/ \
        server/src/main/java/com/hify/conversation/constant/ \
        server/src/test/java/com/hify/conversation/constant/ConversationEnumTest.java
git commit -m "feat(conversation): V10 建 conversation/message 表 + 实体/Mapper/常量（多轮就绪、单轮先行）"
```

---

### Task 3: ConversationStore —— 两个事务 + 读取（按用户过滤）

**Files:**
- Create: `server/src/main/java/com/hify/conversation/service/ConversationStore.java`
- Test: `server/src/test/java/com/hify/conversation/service/ConversationStoreTest.java`

**Interfaces:**
- Consumes: `ConversationMapper`、`MessageMapper`、`Conversation`、`Message`、`MessageRole`、`CommonError.NOT_FOUND`、`BizException`。
- Produces：
  - `openTurn(Long appId, Long conversationId, Long userId, String userContent) : Long` —— **事务A**：conversationId 为 null 则新建会话（title 取首条消息截断 ≤100）+ 落 user 消息；非 null 则校验归属（非本人/不存在抛 404）后落 user 消息。返回 conversationId。
  - `appendAssistant(Long conversationId, String content, int promptTokens, int completionTokens) : Message` —— **事务B**：落 assistant 消息 + touch 会话 update_time。返回落库后的 Message（含 id/createTime）。
  - `listMessages(Long conversationId, Long userId) : List<Message>` —— 读：校验归属后按 id 升序列出消息。

- [ ] **Step 1: 写失败测试**

`ConversationStoreTest.java`：
```java
package com.hify.conversation.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.conversation.constant.MessageRole;
import com.hify.conversation.entity.Conversation;
import com.hify.conversation.entity.Message;
import com.hify.conversation.mapper.ConversationMapper;
import com.hify.conversation.mapper.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationStoreTest {

    private ConversationMapper conversationMapper;
    private MessageMapper messageMapper;
    private ConversationStore store;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        messageMapper = mock(MessageMapper.class);
        store = new ConversationStore(conversationMapper, messageMapper);
    }

    // 模拟 MyBatis-Plus insert 回填自增 id
    private void stubInsertAssignsId(Long id) {
        when(conversationMapper.insert(any(Conversation.class))).thenAnswer((InvocationOnMock inv) -> {
            inv.getArgument(0, Conversation.class).setId(id);
            return 1;
        });
    }

    @Test
    void openTurn_新会话_建会话取title并落user消息_返回新id() {
        stubInsertAssignsId(100L);
        ArgumentCaptor<Conversation> cc = ArgumentCaptor.forClass(Conversation.class);
        ArgumentCaptor<Message> mc = ArgumentCaptor.forClass(Message.class);

        Long cid = store.openTurn(7L, null, 42L, "  你好，介绍一下你自己  ");

        assertEquals(100L, cid);
        verify(conversationMapper).insert(cc.capture());
        assertEquals(7L, cc.getValue().getAppId());
        assertEquals(42L, cc.getValue().getUserId());
        assertEquals("你好，介绍一下你自己", cc.getValue().getTitle()); // 已 strip
        verify(messageMapper).insert(mc.capture());
        assertEquals(100L, mc.getValue().getConversationId());
        assertEquals(MessageRole.USER.value(), mc.getValue().getRole());
        assertEquals("你好，介绍一下你自己", mc.getValue().getContent());
    }

    @Test
    void openTurn_续聊本人会话_不建会话_只落user消息() {
        Conversation existing = new Conversation();
        existing.setId(100L);
        existing.setUserId(42L);
        when(conversationMapper.selectById(eq(100L))).thenReturn(existing);

        Long cid = store.openTurn(7L, 100L, 42L, "继续");

        assertEquals(100L, cid);
        verify(conversationMapper, never()).insert(any());
        verify(messageMapper).insert(any(Message.class));
    }

    @Test
    void openTurn_续聊他人会话_404() {
        Conversation other = new Conversation();
        other.setId(100L);
        other.setUserId(999L);
        when(conversationMapper.selectById(eq(100L))).thenReturn(other);

        BizException ex = assertThrows(BizException.class,
                () -> store.openTurn(7L, 100L, 42L, "继续"));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
        verify(messageMapper, never()).insert(any());
    }

    @Test
    void appendAssistant_落assistant消息含token_并touch会话() {
        ArgumentCaptor<Message> mc = ArgumentCaptor.forClass(Message.class);
        Message saved = store.appendAssistant(100L, "你好，我是助手", 12, 8);

        verify(messageMapper).insert(mc.capture());
        assertEquals(MessageRole.ASSISTANT.value(), mc.getValue().getRole());
        assertEquals("你好，我是助手", mc.getValue().getContent());
        assertEquals(12, mc.getValue().getPromptTokens());
        assertEquals(8, mc.getValue().getCompletionTokens());
        verify(conversationMapper).updateById(any(Conversation.class)); // touch update_time
        assertEquals(mc.getValue(), saved);
    }

    @Test
    void listMessages_他人会话_404() {
        Conversation other = new Conversation();
        other.setUserId(999L);
        when(conversationMapper.selectById(eq(100L))).thenReturn(other);
        BizException ex = assertThrows(BizException.class, () -> store.listMessages(100L, 42L));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && mvn -Dtest=ConversationStoreTest test`
Expected: 编译失败（`ConversationStore` 不存在）。

- [ ] **Step 3: 写实现**

`ConversationStore.java`：
```java
package com.hify.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.conversation.constant.MessageRole;
import com.hify.conversation.entity.Conversation;
import com.hify.conversation.entity.Message;
import com.hify.conversation.mapper.ConversationMapper;
import com.hify.conversation.mapper.MessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会话/消息落库与读取。所有 @Transactional 收口在此（与编排层 ConversationService 分离，
 * 确保 LLM 调用发生在两个独立事务之间、不被任何事务包裹——CLAUDE.md 硬规则 6）。
 */
@Service
public class ConversationStore {

    private static final int TITLE_MAX = 100;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public ConversationStore(ConversationMapper conversationMapper, MessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    /** 事务A：解析/新建会话 + 落 user 消息，返回 conversationId。 */
    @Transactional
    public Long openTurn(Long appId, Long conversationId, Long userId, String userContent) {
        Long cid;
        if (conversationId == null) {
            Conversation c = new Conversation();
            c.setAppId(appId);
            c.setUserId(userId);
            c.setTitle(titleFrom(userContent));
            conversationMapper.insert(c);
            cid = c.getId();
        } else {
            assertOwned(conversationId, userId);
            cid = conversationId;
        }
        Message user = new Message();
        user.setConversationId(cid);
        user.setRole(MessageRole.USER.value());
        user.setContent(userContent.strip());
        messageMapper.insert(user);
        return cid;
    }

    /** 事务B：落 assistant 消息 + touch 会话 update_time。 */
    @Transactional
    public Message appendAssistant(Long conversationId, String content, int promptTokens, int completionTokens) {
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setRole(MessageRole.ASSISTANT.value());
        m.setContent(content);
        m.setPromptTokens(promptTokens);
        m.setCompletionTokens(completionTokens);
        messageMapper.insert(m);
        Conversation touch = new Conversation();
        touch.setId(conversationId);
        conversationMapper.updateById(touch); // update_time 由 infra MetaObjectHandler 自动填充
        return m;
    }

    /** 读：列出某会话消息（按 id 升序）。会话非本人/不存在抛 404。 */
    public List<Message> listMessages(Long conversationId, Long userId) {
        assertOwned(conversationId, userId);
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getId));
    }

    private void assertOwned(Long conversationId, Long userId) {
        Conversation c = conversationMapper.selectById(conversationId);
        if (c == null || !userId.equals(c.getUserId())) {
            throw new BizException(CommonError.NOT_FOUND, "会话不存在");
        }
    }

    private static String titleFrom(String content) {
        String t = content.strip();
        return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && mvn -Dtest=ConversationStoreTest test`
Expected: `Tests run: 5, Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/java/com/hify/conversation/service/ConversationStore.java \
        server/src/test/java/com/hify/conversation/service/ConversationStoreTest.java
git commit -m "feat(conversation): ConversationStore 两事务落库 + 按用户过滤读取"
```

---

### Task 4: 编排层 —— QuotaGuard + ChatInvoker + ConversationService + 响应 DTO

**Files:**
- Create: `server/src/main/java/com/hify/conversation/service/QuotaGuard.java`
- Create: `server/src/main/java/com/hify/conversation/service/ChatInvoker.java`
- Create: `server/src/main/java/com/hify/conversation/service/LlmReply.java`
- Create: `server/src/main/java/com/hify/conversation/dto/MessageView.java`
- Create: `server/src/main/java/com/hify/conversation/dto/SendMessageResponse.java`
- Create: `server/src/main/java/com/hify/conversation/service/ConversationService.java`
- Test: `server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java`

**Interfaces:**
- Consumes: `AppFacade.findRunnableChatApp`（Task 1）、`AppRuntimeView`、`AppConfig`、`ProviderFacade.getChatClient`、`ChatClient`(Spring AI)、`ConversationStore`（Task 3）、`Message`、`CurrentUser`、`ProviderError.MODEL_NOT_USABLE`、`ConversationError.APP_NOT_RUNNABLE`、`BizException`。
- Produces：
  - `record LlmReply(String content, int promptTokens, int completionTokens)`
  - `QuotaGuard.check(Long userId, Long appId) : void`（本轮空实现）
  - `ChatInvoker.invoke(ChatClient client, String systemPrompt, String userContent) : LlmReply`
  - `record MessageView(Long id, String role, String content, Integer promptTokens, Integer completionTokens, OffsetDateTime createTime)`
  - `record SendMessageResponse(Long conversationId, MessageView message)`
  - `ConversationService.send(Long appId, Long conversationId, String content, CurrentUser current) : SendMessageResponse`
  - `ConversationService.history(Long conversationId, CurrentUser current) : List<MessageView>`

- [ ] **Step 1: 写失败测试（编排时序 + 错误码 + 单轮 + 配额锚点）**

`ConversationServiceTest.java`：
```java
package com.hify.conversation.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.dto.AppConfig;
import com.hify.app.api.dto.AppRuntimeView;
import com.hify.common.exception.BizException;
import com.hify.conversation.constant.ConversationError;
import com.hify.conversation.constant.MessageRole;
import com.hify.conversation.dto.SendMessageResponse;
import com.hify.conversation.entity.Message;
import com.hify.infra.security.CurrentUser;
import com.hify.provider.api.ProviderFacade;
import com.hify.provider.constant.ProviderError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    private AppFacade appFacade;
    private ProviderFacade providerFacade;
    private ChatInvoker chatInvoker;
    private ConversationStore store;
    private QuotaGuard quotaGuard;
    private ConversationService service;

    private final CurrentUser member = new CurrentUser(42L, "alice", CurrentUser.ROLE_MEMBER);
    private final ChatClient chatClient = mock(ChatClient.class);

    @BeforeEach
    void setUp() {
        appFacade = mock(AppFacade.class);
        providerFacade = mock(ProviderFacade.class);
        chatInvoker = mock(ChatInvoker.class);
        store = mock(ConversationStore.class);
        quotaGuard = mock(QuotaGuard.class);
        service = new ConversationService(appFacade, providerFacade, chatInvoker, store, quotaGuard);
    }

    private void stubRunnableApp(String systemPrompt) {
        when(appFacade.findRunnableChatApp(eq(7L)))
                .thenReturn(Optional.of(new AppRuntimeView(7L, 5L, new AppConfig(systemPrompt))));
        when(providerFacade.getChatClient(eq(5L))).thenReturn(chatClient);
    }

    private Message savedAssistant() {
        Message m = new Message();
        m.setId(200L);
        m.setConversationId(100L);
        m.setRole(MessageRole.ASSISTANT.value());
        m.setContent("你好，我是助手");
        m.setPromptTokens(12);
        m.setCompletionTokens(8);
        return m;
    }

    @Test
    void send_新会话_三段时序_配额先行_返回assistant视图() {
        stubRunnableApp("你是客服");
        when(store.openTurn(eq(7L), eq(null), eq(42L), eq("你好"))).thenReturn(100L);
        when(chatInvoker.invoke(eq(chatClient), eq("你是客服"), eq("你好")))
                .thenReturn(new LlmReply("你好，我是助手", 12, 8));
        when(store.appendAssistant(eq(100L), eq("你好，我是助手"), eq(12), eq(8)))
                .thenReturn(savedAssistant());

        SendMessageResponse resp = service.send(7L, null, "你好", member);

        // 配额检查在落库前
        InOrder order = inOrder(quotaGuard, store, chatInvoker);
        order.verify(quotaGuard).check(42L, 7L);
        order.verify(store).openTurn(7L, null, 42L, "你好");
        order.verify(chatInvoker).invoke(chatClient, "你是客服", "你好");
        order.verify(store).appendAssistant(100L, "你好，我是助手", 12, 8);

        assertEquals(100L, resp.conversationId());
        assertEquals(200L, resp.message().id());
        assertEquals("assistant", resp.message().role());
        assertEquals("你好，我是助手", resp.message().content());
        assertEquals(12, resp.message().promptTokens());
    }

    @Test
    void send_单轮_只把当前消息喂模型_不含历史() {
        stubRunnableApp(null);
        when(store.openTurn(any(), any(), any(), any())).thenReturn(100L);
        when(chatInvoker.invoke(any(), any(), any())).thenReturn(new LlmReply("ok", 1, 1));
        when(store.appendAssistant(any(), any(), any(), any())).thenReturn(savedAssistant());

        service.send(7L, 100L, "第二句", member);

        ArgumentCaptor<String> sys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> usr = ArgumentCaptor.forClass(String.class);
        verify(chatInvoker).invoke(eq(chatClient), sys.capture(), usr.capture());
        assertEquals(null, sys.getValue());        // systemPrompt 为 null 透传
        assertEquals("第二句", usr.getValue());     // 只当前消息，无历史
    }

    @Test
    void send_应用不可对话_17001_且不落库不调模型() {
        when(appFacade.findRunnableChatApp(eq(7L))).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));
        assertEquals(ConversationError.APP_NOT_RUNNABLE, ex.errorCode());
        verify(store, never()).openTurn(any(), any(), any(), any());
        verify(chatInvoker, never()).invoke(any(), any(), any());
    }

    @Test
    void send_模型不可用_透传12002_user消息已落但不落assistant() {
        when(appFacade.findRunnableChatApp(eq(7L)))
                .thenReturn(Optional.of(new AppRuntimeView(7L, 5L, new AppConfig(null))));
        when(store.openTurn(any(), any(), any(), any())).thenReturn(100L);
        when(providerFacade.getChatClient(eq(5L)))
                .thenThrow(new BizException(ProviderError.MODEL_NOT_USABLE));

        BizException ex = assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));
        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
        verify(store).openTurn(7L, null, 42L, "你好"); // 事务A 已发生
        verify(store, never()).appendAssistant(any(), any(), any(), any());
    }

    @Test
    void send_模型调用故障_透传12003_不落assistant() {
        stubRunnableApp(null);
        when(store.openTurn(any(), any(), any(), any())).thenReturn(100L);
        when(chatInvoker.invoke(any(), any(), any()))
                .thenThrow(new BizException(ProviderError.PROVIDER_UNAVAILABLE));

        BizException ex = assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));
        assertEquals(ProviderError.PROVIDER_UNAVAILABLE, ex.errorCode());
        verify(store, never()).appendAssistant(any(), any(), any(), any());
    }

    @Test
    void history_委托store_按当前用户过滤() {
        Message m = savedAssistant();
        when(store.listMessages(eq(100L), eq(42L))).thenReturn(java.util.List.of(m));

        var list = service.history(100L, member);

        assertEquals(1, list.size());
        assertEquals(200L, list.get(0).id());
        verify(store).listMessages(100L, 42L);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && mvn -Dtest=ConversationServiceTest test`
Expected: 编译失败（编排层类未建）。

- [ ] **Step 3: 写实现**

`QuotaGuard.java`：
```java
package com.hify.conversation.service;

import org.springframework.stereotype.Service;

/**
 * 配额检查锚点（CLAUDE.md：配额只在 conversation 收消息 / workflow 触发两处）。
 * 本轮 usage 模块为空，先空实现放行；usage 就绪后改为委托 UsageFacade.checkQuota，
 * 配额耗尽抛 14001/429。改这一处即可，不动 ConversationService 控制流。
 */
@Service
public class QuotaGuard {

    public void check(Long userId, Long appId) {
        // TODO(usage 轮): UsageFacade.checkQuota(userId, appId)
    }
}
```

`LlmReply.java`：
```java
package com.hify.conversation.service;

/** 单次非流式模型调用结果（模块内）：正文 + token 用量。 */
public record LlmReply(String content, int promptTokens, int completionTokens) {
}
```

`ChatInvoker.java`：
```java
package com.hify.conversation.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Spring AI ChatClient 的薄适配层（仿 provider 的 ModelConnectionService）：把流式 API 收敛成 LlmReply。
 * 不带 @Transactional——这里发生真实外部 IO。systemPrompt 空白则不加 system 段。
 * 单元测试不覆盖本类（薄适配，真实调用由收尾自检手验，见 self-check）；ConversationService 测试 mock 它。
 */
@Service
public class ChatInvoker {

    public LlmReply invoke(ChatClient chatClient, String systemPrompt, String userContent) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt().user(userContent);
        if (StringUtils.hasText(systemPrompt)) {
            spec = spec.system(systemPrompt);
        }
        ChatResponse resp = spec.call().chatResponse();
        String content = resp.getResult().getOutput().getText();
        Usage usage = resp.getMetadata().getUsage();
        int promptTokens = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int completionTokens = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        return new LlmReply(content, promptTokens, completionTokens);
    }
}
```

`MessageView.java`：
```java
package com.hify.conversation.dto;

import java.time.OffsetDateTime;

/**
 * 消息视图（成员族响应）。id 为 Long（infra 全局序列化为 string）；token 数同样。
 * role 取 'user'/'assistant'。
 */
public record MessageView(
        Long id,
        String role,
        String content,
        Integer promptTokens,
        Integer completionTokens,
        OffsetDateTime createTime) {
}
```

`SendMessageResponse.java`：
```java
package com.hify.conversation.dto;

/** 发消息响应：本次会话 id（前端续聊用）+ assistant 消息视图。 */
public record SendMessageResponse(Long conversationId, MessageView message) {
}
```

`ConversationService.java`：
```java
package com.hify.conversation.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.dto.AppRuntimeView;
import com.hify.common.exception.BizException;
import com.hify.conversation.constant.ConversationError;
import com.hify.conversation.dto.MessageView;
import com.hify.conversation.dto.SendMessageResponse;
import com.hify.conversation.entity.Message;
import com.hify.infra.security.CurrentUser;
import com.hify.provider.api.ProviderFacade;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 单轮聊天编排。本类**不带 @Transactional**：事务边界全在 ConversationStore，
 * LLM 调用（chatInvoker.invoke）夹在两个事务之间、不被任何事务包裹（CLAUDE.md 硬规则 6）。
 * 单轮：组 prompt 只含 systemPrompt + 当前消息，不读历史。
 */
@Service
public class ConversationService {

    private final AppFacade appFacade;
    private final ProviderFacade providerFacade;
    private final ChatInvoker chatInvoker;
    private final ConversationStore store;
    private final QuotaGuard quotaGuard;

    public ConversationService(AppFacade appFacade, ProviderFacade providerFacade,
                               ChatInvoker chatInvoker, ConversationStore store, QuotaGuard quotaGuard) {
        this.appFacade = appFacade;
        this.providerFacade = providerFacade;
        this.chatInvoker = chatInvoker;
        this.store = store;
        this.quotaGuard = quotaGuard;
    }

    public SendMessageResponse send(Long appId, Long conversationId, String content, CurrentUser current) {
        // 1) 配额锚点（本轮放行）
        quotaGuard.check(current.userId(), appId);
        // 2) 校验应用可对话（读，无事务）
        AppRuntimeView app = appFacade.findRunnableChatApp(appId)
                .orElseThrow(() -> new BizException(ConversationError.APP_NOT_RUNNABLE));
        // 3) 事务A：建/取会话 + 落 user 消息
        Long cid = store.openTurn(appId, conversationId, current.userId(), content);
        // 4) 取 ChatClient（不可用抛 12002）并调用——事务外
        ChatClient chatClient = providerFacade.getChatClient(app.modelId());
        LlmReply reply = chatInvoker.invoke(chatClient, app.config().systemPrompt(), content);
        // 5) 事务B：落 assistant 消息
        Message saved = store.appendAssistant(cid, reply.content(), reply.promptTokens(), reply.completionTokens());
        return new SendMessageResponse(cid, toView(saved));
    }

    public List<MessageView> history(Long conversationId, CurrentUser current) {
        return store.listMessages(conversationId, current.userId()).stream()
                .map(ConversationService::toView)
                .toList();
    }

    private static MessageView toView(Message m) {
        return new MessageView(m.getId(), m.getRole(), m.getContent(),
                m.getPromptTokens(), m.getCompletionTokens(), m.getCreateTime());
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && mvn -Dtest=ConversationServiceTest test`
Expected: `Tests run: 6, Failures: 0, Errors: 0`。

- [ ] **Step 5: 模块边界回归**

Run: `cd server && mvn -Dtest=ModularityTests,LayerRulesTest test`
Expected: 全绿（service 引 app::api/provider::api 合规；dto 不 import entity；无 @Transactional 越界——本类无事务注解、Store 才有）。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/java/com/hify/conversation/service/ \
        server/src/main/java/com/hify/conversation/dto/ \
        server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java
git commit -m "feat(conversation): 编排层 ConversationService（三段时序/单轮/配额锚点）+ ChatInvoker/QuotaGuard/DTO"
```

---

### Task 5: ConversationController —— 成员族 REST 接口

**Files:**
- Create: `server/src/main/java/com/hify/conversation/dto/SendMessageRequest.java`
- Create: `server/src/main/java/com/hify/conversation/controller/ConversationController.java`
- Test: `server/src/test/java/com/hify/conversation/controller/ConversationControllerTest.java`

**Interfaces:**
- Consumes: `ConversationService.send/history`、`SendMessageResponse`、`MessageView`、`Result`、`CurrentUserHolder`。
- Produces：
  - `record SendMessageRequest(@NotNull Long appId, Long conversationId, @NotBlank String content)`
  - `POST /api/v1/conversation/messages` → `Result<SendMessageResponse>`
  - `GET /api/v1/conversation/messages?conversationId=` → `Result<List<MessageView>>`（裸数组，与 provider models 端点一致）

- [ ] **Step 1: 写失败测试（@WebMvcTest，仿 AppControllerTest）**

`ConversationControllerTest.java`：
```java
package com.hify.conversation.controller;

import com.hify.conversation.dto.MessageView;
import com.hify.conversation.dto.SendMessageResponse;
import com.hify.conversation.service.ConversationService;
import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConversationController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class ConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ConversationService conversationService;

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(42L, "alice", CurrentUser.ROLE_MEMBER));
    }

    private MessageView assistant() {
        return new MessageView(200L, "assistant", "你好，我是助手", 12, 8,
                OffsetDateTime.parse("2026-06-26T10:00:00+08:00"));
    }

    @Test
    void 发消息_成员可访问_Long为string() throws Exception {
        when(conversationService.send(eq(7L), eq(null), eq("你好"), any()))
                .thenReturn(new SendMessageResponse(100L, assistant()));
        mockMvc.perform(post("/api/v1/conversation/messages")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"appId\":\"7\",\"content\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.conversationId").value("100"))   // Long→string
                .andExpect(jsonPath("$.data.message.id").value("200"))
                .andExpect(jsonPath("$.data.message.role").value("assistant"))
                .andExpect(jsonPath("$.data.message.promptTokens").value("12")); // 数字也 string
    }

    @Test
    void 发消息_content为空_400带字段错误() throws Exception {
        mockMvc.perform(post("/api/v1/conversation/messages")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"appId\":\"7\",\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void 拉历史_返回数组() throws Exception {
        when(conversationService.history(eq(100L), any())).thenReturn(List.of(assistant()));
        mockMvc.perform(get("/api/v1/conversation/messages?conversationId=100")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("200"));
    }

    @Test
    void 未登录_401() throws Exception {
        mockMvc.perform(get("/api/v1/conversation/messages?conversationId=100"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && mvn -Dtest=ConversationControllerTest test`
Expected: 编译失败（Controller/Request 不存在）。

- [ ] **Step 3: 写实现**

`SendMessageRequest.java`：
```java
package com.hify.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 发消息请求。appId 必填；conversationId 可空（空=新建会话）；content 必填非空白（全局 trim）。
 * 校验注解只写在本层。
 */
public record SendMessageRequest(
        @NotNull Long appId,
        Long conversationId,
        @NotBlank String content) {
}
```

`ConversationController.java`：
```java
package com.hify.conversation.controller;

import com.hify.common.Result;
import com.hify.conversation.dto.MessageView;
import com.hify.conversation.dto.SendMessageRequest;
import com.hify.conversation.dto.SendMessageResponse;
import com.hify.conversation.service.ConversationService;
import com.hify.infra.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话消息接口（成员族 /api/v1/conversation/**，按当前用户过滤）。
 * 协议层：@Valid → 取当前用户 → 调 service → 包 Result；无业务逻辑、无 @Transactional。
 * 本轮一次性 .call() 返回（SSE 留下一轮）。
 */
@RestController
@RequestMapping("/api/v1/conversation/messages")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public Result<SendMessageResponse> send(@Valid @RequestBody SendMessageRequest request) {
        return Result.ok(conversationService.send(
                request.appId(), request.conversationId(), request.content(), CurrentUserHolder.current()));
    }

    @GetMapping
    public Result<List<MessageView>> history(@RequestParam Long conversationId) {
        return Result.ok(conversationService.history(conversationId, CurrentUserHolder.current()));
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && mvn -Dtest=ConversationControllerTest test`
Expected: `Tests run: 4, Failures: 0, Errors: 0`。

- [ ] **Step 5: 后端全量回归**

Run: `cd server && mvn test`
Expected: Surefire 汇总 `Failures: 0, Errors: 0`（含 ModularityTests / LayerRulesTest）。逐项读输出，不 grep `BUILD SUCCESS`。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/java/com/hify/conversation/controller/ \
        server/src/main/java/com/hify/conversation/dto/SendMessageRequest.java \
        server/src/test/java/com/hify/conversation/controller/ConversationControllerTest.java
git commit -m "feat(conversation): 成员族 POST/GET /api/v1/conversation/messages（一次性 .call）"
```

---

### Task 6: 前端 types + api 层

**Files:**
- Create: `web/src/types/conversation.ts`
- Create: `web/src/api/conversation.ts`
- Test: `web/src/api/__tests__/conversation.spec.ts`

**Interfaces:**
- Produces：
  - `types`: `MessageRole = 'user'|'assistant'`；`MessageView`（id/role/content/promptTokens/completionTokens/createTime，id 为 string）；`SendMessageResponse{ conversationId: string; message: MessageView }`
  - `api`: `sendMessage(appId, conversationId|null, content) → request.post('/conversation/messages', {appId, conversationId, content})`；`getMessages(conversationId) → request.get('/conversation/messages', {params:{conversationId}})`

- [ ] **Step 1: 写失败测试（仿 app.spec.ts）**

`web/src/api/__tests__/conversation.spec.ts`：
```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import { sendMessage, getMessages } from '@/api/conversation'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

describe('conversation api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('sendMessage → POST /conversation/messages + body', () => {
    sendMessage('7', null, '你好')
    expect(request.post).toHaveBeenCalledWith('/conversation/messages', {
      appId: '7', conversationId: null, content: '你好',
    })
  })

  it('sendMessage 续聊带 conversationId', () => {
    sendMessage('7', '100', '继续')
    expect(request.post).toHaveBeenCalledWith('/conversation/messages', {
      appId: '7', conversationId: '100', content: '继续',
    })
  })

  it('getMessages → GET /conversation/messages?conversationId', () => {
    getMessages('100')
    expect(request.get).toHaveBeenCalledWith('/conversation/messages', {
      params: { conversationId: '100' },
    })
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web && pnpm vitest run src/api/__tests__/conversation.spec.ts`
Expected: 失败（模块 `@/api/conversation` 不存在）。

- [ ] **Step 3: 写实现**

`web/src/types/conversation.ts`：
```ts
/** 消息角色（对齐后端 message.role）。 */
export type MessageRole = 'user' | 'assistant'

/** 消息视图（对齐后端 MessageView）。id/token 为 string（Long 序列化防精度丢失）。 */
export interface MessageView {
  id: string
  role: MessageRole
  content: string
  promptTokens: string | null
  completionTokens: string | null
  createTime: string
}

/** 发消息响应（对齐后端 SendMessageResponse）。 */
export interface SendMessageResponse {
  conversationId: string
  message: MessageView
}
```

`web/src/api/conversation.ts`：
```ts
import { request } from '@/api/request'
import type { MessageView, SendMessageResponse } from '@/types/conversation'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。成员资源，放 api/ 根。
const BASE = '/conversation/messages'

/** 发消息（本轮一次性返回）。conversationId 传 null 表示新建会话。后端：POST /api/v1/conversation/messages */
export function sendMessage(appId: string, conversationId: string | null, content: string) {
  return request.post<SendMessageResponse>(BASE, { appId, conversationId, content })
}

/** 拉某会话历史消息。后端：GET /api/v1/conversation/messages?conversationId= */
export function getMessages(conversationId: string) {
  return request.get<MessageView[]>(BASE, { params: { conversationId } })
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd web && pnpm vitest run src/api/__tests__/conversation.spec.ts`
Expected: 3 passed。

- [ ] **Step 5: 提交**

```bash
git add web/src/types/conversation.ts web/src/api/conversation.ts web/src/api/__tests__/conversation.spec.ts
git commit -m "feat(web): conversation api 层 + 类型（sendMessage/getMessages）"
```

---

### Task 7: 前端 ChatView 页面 + 路由

**Files:**
- Create: `web/src/views/conversation/ChatView.vue`
- Modify: `web/src/router/index.ts`（新增 `/apps/:appId/chat` 路由）
- Test: `web/src/views/conversation/__tests__/ChatView.spec.ts`

**Interfaces:**
- Consumes: `sendMessage`（Task 6）、`useRoute().params.appId`。
- Produces: 路由 name `AppChat`，path `/apps/:appId/chat`。data-test 锚点：`chat-input`、`chat-send`、`msg`。

- [ ] **Step 1: 写失败测试（仿 AppList.spec.ts，mock api 与 vue-router）**

`web/src/views/conversation/__tests__/ChatView.spec.ts`：
```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { sendMessage } from '@/api/conversation'
import ChatView from '@/views/conversation/ChatView.vue'

vi.mock('@/api/conversation', () => ({ sendMessage: vi.fn(), getMessages: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { appId: '7' } }) }))

globalThis.ResizeObserver = class {
  observe() {} unobserve() {} disconnect() {}
} as unknown as typeof ResizeObserver

describe('ChatView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(sendMessage).mockResolvedValue({
      conversationId: '100',
      message: {
        id: '200', role: 'assistant', content: '你好，我是助手',
        promptTokens: '12', completionTokens: '8', createTime: '2026-06-26T10:00:00+08:00',
      },
    })
  })

  it('发送后渲染用户气泡与助手回复，并以 appId+conversationId 调用', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })

    await wrapper.find('[data-test="chat-input"] textarea').setValue('你好')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()

    // 首次发送：conversationId 为 null
    expect(sendMessage).toHaveBeenCalledWith('7', null, '你好')
    const bubbles = wrapper.findAll('[data-test="msg"]')
    expect(bubbles).toHaveLength(2)            // user + assistant
    expect(wrapper.text()).toContain('你好')
    expect(wrapper.text()).toContain('你好，我是助手')
  })

  it('续聊复用上一次返回的 conversationId', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })

    await wrapper.find('[data-test="chat-input"] textarea').setValue('第一句')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()

    await wrapper.find('[data-test="chat-input"] textarea').setValue('第二句')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()

    expect(sendMessage).toHaveBeenNthCalledWith(1, '7', null, '第一句')
    expect(sendMessage).toHaveBeenNthCalledWith(2, '7', '100', '第二句') // 复用返回的会话 id
  })

  it('空白输入不触发发送', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await wrapper.find('[data-test="chat-input"] textarea').setValue('   ')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()
    expect(sendMessage).not.toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ChatView.spec.ts`
Expected: 失败（`ChatView.vue` 不存在）。

- [ ] **Step 3: 写实现**

`web/src/views/conversation/ChatView.vue`：
```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { sendMessage } from '@/api/conversation'
import type { MessageView } from '@/types/conversation'

const route = useRoute()
const appId = route.params.appId as string

const messages = ref<MessageView[]>([])
const conversationId = ref<string | null>(null)
const input = ref('')
const sending = ref(false)

async function onSend() {
  const text = input.value.trim()
  if (!text || sending.value) return
  // 本地先渲染用户气泡（id 用本地占位，不与后端冲突）
  messages.value.push({
    id: `local-${Date.now()}`,
    role: 'user',
    content: text,
    promptTokens: null,
    completionTokens: null,
    createTime: new Date().toISOString(),
  })
  input.value = ''
  sending.value = true
  try {
    const res = await sendMessage(appId, conversationId.value, text)
    conversationId.value = res.conversationId
    messages.value.push(res.message)
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <div class="chat">
    <div class="chat__list">
      <div
        v-for="m in messages"
        :key="m.id"
        :class="['chat__bubble', `chat__bubble--${m.role}`]"
        data-test="msg"
      >
        {{ m.content }}
      </div>
    </div>
    <div class="chat__input">
      <el-input
        v-model="input"
        data-test="chat-input"
        type="textarea"
        :rows="2"
        :disabled="sending"
        placeholder="输入消息，回车或点发送…"
        @keyup.enter.exact.prevent="onSend"
      />
      <el-button type="primary" data-test="chat-send" :loading="sending" @click="onSend">
        发送
      </el-button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.chat {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 16px;
  gap: 12px;

  &__list {
    flex: 1;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__bubble {
    max-width: 70%;
    padding: 8px 12px;
    border-radius: 8px;
    white-space: pre-wrap;
    word-break: break-word;

    &--user {
      align-self: flex-end;
      background: var(--el-color-primary-light-8);
    }

    &--assistant {
      align-self: flex-start;
      background: var(--el-fill-color-light);
    }
  }

  &__input {
    display: flex;
    gap: 8px;
    align-items: flex-end;
  }
}
</style>
```

`web/src/router/index.ts`：在 `/app` 路由记录之后、`/admin/provider` 之前插入：
```ts
  {
    path: '/apps/:appId/chat',
    name: 'AppChat',
    component: () => import('@/views/conversation/ChatView.vue'),
    meta: { requiresAuth: true, title: '试聊' },
  },
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ChatView.spec.ts`
Expected: 3 passed。

- [ ] **Step 5: 提交**

```bash
git add web/src/views/conversation/ChatView.vue web/src/router/index.ts \
        web/src/views/conversation/__tests__/ChatView.spec.ts
git commit -m "feat(web): 极简单会话页 ChatView + /apps/:appId/chat 路由"
```

---

### Task 8: AppList 增加"试聊"入口

**Files:**
- Modify: `web/src/views/app/AppList.vue`（actions 列加"试聊"按钮 + `useRouter`）
- Modify: `web/src/views/app/__tests__/AppList.spec.ts`（mock vue-router + 新增跳转测试）

**Interfaces:**
- Consumes: `useRouter().push`、`App.modelUsable`、`App.status`、`App.id`。
- Produces: 每行"试聊"按钮，`data-test="chat-{id}"`，仅当 `modelUsable && status==='enabled'` 可点；点击 `router.push('/apps/{id}/chat')`。

- [ ] **Step 1: 写失败测试（在 AppList.spec.ts 顶部加 vue-router mock + 新用例）**

在 `AppList.spec.ts` 现有 `vi.mock('@/api/provider', ...)` 之后追加：
```ts
const routerPush = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push: routerPush }) }))
```
并在 `describe('AppList', ...)` 内新增用例（`NAMED` 已是 modelUsable=true 的应用，id='4'）：
```ts
  it('试聊按钮：可用模型应用点击跳转 /apps/{id}/chat', async () => {
    vi.mocked(listApps).mockResolvedValue(page([NAMED]))
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.find('[data-test="chat-4"]').trigger('click')
    expect(routerPush).toHaveBeenCalledWith('/apps/4/chat')
  })

  it('试聊按钮：模型不可用应用禁用', async () => {
    vi.mocked(listApps).mockResolvedValue(page([WITH_MODEL])) // modelUsable=false
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test="chat-3"]').attributes('disabled')).toBeDefined()
  })
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web && pnpm vitest run src/views/app/__tests__/AppList.spec.ts`
Expected: 新两条失败（按钮不存在）；其余原用例仍 passed。

- [ ] **Step 3: 写实现**

`AppList.vue` `<script setup>` 顶部 import 区加：
```ts
import { useRouter } from 'vue-router'
```
在 `const userStore = useUserStore()` 附近加：
```ts
const router = useRouter()

function openChat(app: App) {
  router.push(`/apps/${app.id}/chat`)
}
```
在 actions 列（`<el-table-column label="操作" ...>` 内）最前面、"停用/启用"按钮之前插入：
```vue
            <el-button
              link
              type="primary"
              :data-test="`chat-${row.id}`"
              :disabled="!row.modelUsable || row.status === 'disabled'"
              @click="openChat(row)"
              >试聊</el-button
            >
```
> 注：actions 列 `width="260"` 可能略挤，按需调到 `width="320"`（不影响测试）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd web && pnpm vitest run src/views/app/__tests__/AppList.spec.ts`
Expected: 全部 passed（含新两条与全部原用例）。

- [ ] **Step 5: 提交**

```bash
git add web/src/views/app/AppList.vue web/src/views/app/__tests__/AppList.spec.ts
git commit -m "feat(web): 应用列表新增「试聊」入口（仅可用模型应用可点）"
```

---

### Task 9: 全量回归 + 自检文档

**Files:**
- Create: `docs/self-check-conversation.md`

- [ ] **Step 1: 后端全量测试**

Run: `cd server && mvn test`
Expected: Surefire 汇总 `Failures: 0, Errors: 0`；ModularityTests / LayerRulesTest 绿。逐项读输出。

- [ ] **Step 2: 前端全量测试 + 类型检查 + lint**

Run: `cd web && pnpm test`
Expected: 全部 passed。
Run: `cd web && pnpm build`（含 vue-tsc 类型检查）或 `pnpm type-check`（若有该脚本）
Expected: 无类型错误。

- [ ] **Step 3: 写自检文档**

`docs/self-check-conversation.md`，逐条对照本轮 spec §0 决策与 Global Constraints，记录：
- 6 个决策点的最终落地位置（文件 + 行为）；
- "`@Transactional` 内无 LLM 调用"如何由结构保证（ConversationService 无事务注解、ChatInvoker 在其内被调、Store 才有事务）；
- 单轮如何保证（chatInvoker.invoke 入参只有当前 content）；
- 错误码：17001 新增、12002/12003/12004 透传、404/10004 复用；
- 已知取舍（LLM 失败时 user 消息已落、无补偿）；
- 范围外清单（SSE/多轮/会话列表/配额计量/对外 API）。

- [ ] **Step 4: 真实模型连通手验（仿 C2 deepseek 验证）**

前置：本地库有可用 deepseek（或其他）供应商 + chat 模型 + 一个绑定该模型的对话型应用（admin 账号已 seed，可直接登录建应用）。
手动：登录前端 → 应用列表点"试聊" → 发"你好" → 应收到真实回复并入库。
或用 curl（先登录拿 token）：
```bash
curl -s -X POST http://localhost:8080/api/v1/conversation/messages \
  -H "Authorization: Bearer <member-token>" -H "Content-Type: application/json" \
  -d '{"appId":"<chatAppId>","content":"你好"}'
```
Expected: `code=200`，`data.message.content` 为真实回复，`data.message.promptTokens/completionTokens` 非空。
把结果（成功/失败 + 关键输出）追加进 `docs/self-check-conversation.md`。

- [ ] **Step 5: 提交**

```bash
git add docs/self-check-conversation.md
git commit -m "docs(conversation): 单轮聊天自检（含真实模型连通验证）"
```

---

## Self-Review（计划作者自检，已完成）

**1. Spec 覆盖**：
- §0 决策1（一次性 .call）→ ChatInvoker `.call().chatResponse()` + Controller 同步返回（Task 4/5）。
- §0 决策2（两表/单轮）→ Task 2 建表，Task 4 ConversationService 只喂当前 content。
- §0 决策3（配额锚点）→ Task 4 QuotaGuard 空实现 + 调用点。
- §0 决策4（极简前端）→ Task 7 ChatView 无会话列表。
- §1 事务夹 LLM → Task 3 Store 两事务 + Task 4 Service 无事务编排。
- §3 AppFacade → Task 1。
- §4 路由/错误码 → Task 2（17001）+ Task 5（路由）。
- §6 测试策略 → 各 Task TDD + Task 9 全量。
- §7 配额锚点 → Task 4。所有 spec 节点均有对应 Task。

**2. Placeholder 扫描**：无 TBD/TODO 占位（QuotaGuard 内 `TODO(usage 轮)` 是 spec 明确要求的代码锚点，非计划占位）。每个 code step 给了完整代码。

**3. 类型一致性**：`AppRuntimeView(appId, modelId, config)`、`LlmReply(content, promptTokens, completionTokens)`、`MessageView(id, role, content, promptTokens, completionTokens, createTime)`、`SendMessageResponse(conversationId, message)`、`SendMessageRequest(appId, conversationId, content)`、`ConversationStore.openTurn/appendAssistant/listMessages`、`ChatInvoker.invoke(client, systemPrompt, userContent)`、`ConversationService.send/history` —— 在定义处与使用处签名一致。前端 `sendMessage(appId, conversationId, content)` / `getMessages(conversationId)` 与 Task 6/7/8 调用一致。
