# 聊天引用来源展示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 绑库应用聊天时，把 K4 检索到但当前被丢弃的命中段做成「分段级来源快照」随 assistant 消息落库，并经流式/重载两条前端路径展示为气泡下方可折叠的「参考来源」卡片。

**Architecture:** conversation 模块把 `knowledge.api.RetrievedChunk` 映射为自有 DTO `MessageSource`（含截断预览，不存全文），存进 message 表新增的 `sources jsonb` 列（照抄 `AppConfigTypeHandler` 的 jsonb 手法）。流式路径新增一个只增不改的 SSE `sources` 事件（Meta 后、Delta 前发）；重载路径经 `MessageView.sources` 带出。前端用 Element Plus `el-collapse` 渲染折叠卡片。

**Tech Stack:** Spring Boot 3 / Java 21 / MyBatis-Plus / PostgreSQL(pgvector) / Reactor(SSE) / Vue 3 + TS + Element Plus + Vitest / JUnit5 + Mockito + Testcontainers。

## Global Constraints

- 错误码零新增；既有端点签名/错误码零改动（api-standards.md）。
- Long 一律序列化为 string；Integer/int 与 double 保持 number（infra 全局 Jackson）。
- DTO 不 import entity（ArchUnit 强制）；跨模块 DTO 放 api 顶层包（本轮 `MessageSource` 是 conversation 内部成员族 DTO，放 `conversation.dto`）。
- 跨模块仍只依赖 `knowledge.api.RetrievedChunk`，零新增模块依赖。
- SSE 事件只增不改：`meta`/`message`/`done`/`error` 结构不动，只加 `sources`。
- 只新增 Flyway V20，不改旧迁移；下一个可用版本号 = V20（现有最新 V19）。
- `@Transactional` 内零外部 IO（检索仍在两事务间隙，位置不变）。
- 配置外化到 application.yml，不硬编码（CLAUDE.md）。
- 连库测试继承 `com.hify.support.PgIntegrationTest`（裸命令能跑；本机 Docker 需启动）。
- 前端 TDD：新代码先写失败的 vitest，测试放 `__tests__/`；优先 Element Plus 组件（frontend-standards §5.9）。
- 判定 mvn 结果不要 grep `BUILD SUCCESS`（`-q` 会静音）；看测试计数/失败项。

---

### Task 1: 持久化地基 — `MessageSource` DTO + V20 迁移 + jsonb TypeHandler + Message 实体 + `appendAssistant` 落库

**Files:**
- Create: `server/src/main/java/com/hify/conversation/dto/MessageSource.java`
- Create: `server/src/main/resources/db/migration/V20__message_add_sources.sql`
- Create: `server/src/main/java/com/hify/conversation/config/MessageSourcesTypeHandler.java`
- Modify: `server/src/main/java/com/hify/conversation/entity/Message.java`
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationStore.java:95-110`（`appendAssistant` 加参数）
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java:75-76,117-118`（两处调用先传 `List.of()`，Task 3 换真值）
- Modify: `server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java`（`appendAssistant` mock 桩加一个 `any()` 匹配器）
- Modify: `server/src/test/java/com/hify/conversation/service/ConversationStoreTest.java`（`appendAssistant` 调用加 `List.of()` 实参）
- Test: `server/src/test/java/com/hify/conversation/service/MessageSourcesPersistenceTest.java`

**Interfaces:**
- Produces:
  - `record MessageSource(Long chunkId, Long documentId, String documentName, double score, String preview)`
  - `ConversationStore.appendAssistant(Long conversationId, String content, int promptTokens, int completionTokens, Long userId, Long appId, Long modelId, List<MessageSource> sources)` — 末尾新增 `sources` 参数
  - `Message.getSources() : List<MessageSource>` / `Message.setSources(List<MessageSource>)`（jsonb，读空兜底 `List.of()`）

- [ ] **Step 1: 新建 `MessageSource` DTO**

`server/src/main/java/com/hify/conversation/dto/MessageSource.java`:

```java
package com.hify.conversation.dto;

/**
 * 引用来源快照（一条命中段）。三处共用：MessageView.sources / SSE sources 事件 / message.sources jsonb。
 * chunkId/documentId 全局 Jackson → string；score 为相似度 0~1，保持 number；preview 为 content 截断快照（不含全文）。
 * documentName/preview 存答题那一刻的快照，文档后续改名/删除不影响历史留痕。
 */
public record MessageSource(
        Long chunkId,
        Long documentId,
        String documentName,
        double score,
        String preview) {
}
```

- [ ] **Step 2: 新建 V20 迁移**

`server/src/main/resources/db/migration/V20__message_add_sources.sql`:

```sql
-- V20：message 加来源快照列（conversation 模块）。绑库应用回答的引用依据，随消息一起读/删（会话删级联）。
-- 只新增列，不改 V10；default '[]' 保证存量历史消息读出为空数组，前端不渲染卡片。无新索引（不反查）。
alter table message add column sources jsonb not null default '[]';
comment on column message.sources is '引用来源快照数组 [{chunkId,documentId,documentName,score,preview}]；未绑库/降级/无命中恒为 []';
```

- [ ] **Step 3: 新建 jsonb TypeHandler（照抄 `AppConfigTypeHandler`）**

`server/src/main/java/com/hify/conversation/config/MessageSourcesTypeHandler.java`:

```java
package com.hify.conversation.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.conversation.dto.MessageSource;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * message.sources（jsonb）↔ {@code List<MessageSource>} 的类型处理器（照 AppConfigTypeHandler 手法）。
 * 写出包成 PGobject(type=jsonb)，否则 PG 报「column is of type jsonb but expression is of type varchar」。
 * 读入空/空白兜底为 List.of()，保证字段不为 null。实体需 @TableName(autoResultMap=true) 才启用本处理器。
 */
public class MessageSourcesTypeHandler extends BaseTypeHandler<List<MessageSource>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<MessageSource>> LIST_TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<MessageSource> parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        try {
            obj.setValue(MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("序列化 message.sources 失败", e);
        }
        ps.setObject(i, obj);
    }

    @Override
    public List<MessageSource> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<MessageSource> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<MessageSource> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<MessageSource> parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new SQLException("反序列化 message.sources 失败", e);
        }
    }
}
```

- [ ] **Step 4: Message 实体加 `sources` 字段 + 启用 autoResultMap**

改 `server/src/main/java/com/hify/conversation/entity/Message.java`：类注解与新字段。完整改后文件：

```java
package com.hify.conversation.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;
import com.hify.conversation.config.MessageSourcesTypeHandler;
import com.hify.conversation.dto.MessageSource;

import java.util.List;

/**
 * 消息表 {@code message} 映射实体。role 存 'user'/'assistant'（见 MessageRole）。
 * tool_calls(jsonb) 本轮恒空、不映射字段（DB 默认 '[]'），留待 Agent 轨迹。
 * sources(jsonb) 经 {@link MessageSourcesTypeHandler} 读写；autoResultMap=true 才让处理器在查询映射生效。
 */
@TableName(value = "message", autoResultMap = true)
public class Message extends BaseEntity {

    private Long conversationId;
    private String role;
    private String content;
    private Integer promptTokens;
    private Integer completionTokens;

    @TableField(typeHandler = MessageSourcesTypeHandler.class)
    private List<MessageSource> sources;

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

    public List<MessageSource> getSources() { return sources; }
    public void setSources(List<MessageSource> sources) { this.sources = sources; }
}
```

- [ ] **Step 5: 写失败的持久化往返测试**

`server/src/test/java/com/hify/conversation/service/MessageSourcesPersistenceTest.java`:

```java
package com.hify.conversation.service;

import com.hify.conversation.dto.MessageSource;
import com.hify.conversation.entity.Conversation;
import com.hify.conversation.entity.Message;
import com.hify.conversation.mapper.ConversationMapper;
import com.hify.conversation.mapper.MessageMapper;
import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageSourcesPersistenceTest extends PgIntegrationTest {

    @Autowired MessageMapper messageMapper;
    @Autowired ConversationMapper conversationMapper;

    private Long newConversation() {
        Conversation c = new Conversation();
        c.setAppId(1L);
        c.setUserId(42L);
        conversationMapper.insert(c);
        return c.getId();
    }

    @Test
    void sources_roundtrip_through_jsonb() {
        Long cid = newConversation();
        Message m = new Message();
        m.setConversationId(cid);
        m.setRole("assistant");
        m.setContent("答案正文");
        m.setPromptTokens(1);
        m.setCompletionTokens(2);
        m.setSources(List.of(new MessageSource(10L, 20L, "产品手册.pdf", 0.82, "安装前需确认电压范围")));
        messageMapper.insert(m);

        Message read = messageMapper.selectById(m.getId());
        assertEquals(1, read.getSources().size());
        MessageSource s = read.getSources().get(0);
        assertEquals(10L, s.chunkId());
        assertEquals("产品手册.pdf", s.documentName());
        assertEquals(0.82, s.score());
        assertEquals("安装前需确认电压范围", s.preview());
    }

    @Test
    void legacy_message_without_sources_reads_empty_list() {
        Long cid = newConversation();
        // 模拟存量：不 set sources，走 DB default '[]'
        Message m = new Message();
        m.setConversationId(cid);
        m.setRole("user");
        m.setContent("历史消息");
        messageMapper.insert(m);

        Message read = messageMapper.selectById(m.getId());
        assertTrue(read.getSources().isEmpty());
    }
}
```

- [ ] **Step 6: 跑测试确认失败（`appendAssistant` 签名尚未改、编译已能过 entity 部分）**

Run: `cd server && ./mvnw -q -Dtest=MessageSourcesPersistenceTest test`
Expected: 两个用例通过（Step 1–4 已实现往返能力）。若 `legacy_...` 报 sources 为 null，说明 autoResultMap/typeHandler 未生效——回查 Step 4 注解。

> 注：本 Task 的 TDD「红」体现在 Task 3/4 的行为测试；本步是地基往返验证，Step 1–4 完成即应绿。

- [ ] **Step 7: `appendAssistant` 加 `sources` 参数并落库**

改 `ConversationStore.java` 的 `appendAssistant`（第 95-110 行）：签名末尾加 `List<MessageSource> sources`，insert 前 `m.setSources(sources)`。改后方法：

```java
    @Transactional
    public Message appendAssistant(Long conversationId, String content, int promptTokens, int completionTokens,
                                   Long userId, Long appId, Long modelId, List<com.hify.conversation.dto.MessageSource> sources) {
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setRole(MessageRole.ASSISTANT.value());
        m.setContent(content);
        m.setPromptTokens(promptTokens);
        m.setCompletionTokens(completionTokens);
        m.setSources(sources);
        messageMapper.insert(m);
        Conversation touch = new Conversation();
        touch.setId(conversationId);
        conversationMapper.updateById(touch);
        publisher.publishEvent(new TokenUsedEvent(userId, appId, modelId, promptTokens, completionTokens));
        return m;
    }
```

（`import java.util.List;` 已在文件中；`MessageSource` 用全限定或加 import。）

- [ ] **Step 8: 更新两处调用与既有测试桩，恢复编译**

1. `ConversationService.java` 两处 `store.appendAssistant(...)`（send 第 75、sendStream 第 117 行）末尾加实参 `java.util.List.of()`（临时占位，Task 3 换真值）。
2. `ConversationServiceTest.java`：`when(store.appendAssistant(...))` 桩末尾加一个 `any()` 匹配器（沿用现有 `anyLong()/any()` 风格，导入 `org.mockito.ArgumentMatchers.any`）。
3. `ConversationStoreTest.java`：所有 `appendAssistant(...)` 调用末尾加 `java.util.List.of()` 实参。

- [ ] **Step 9: 全量编译 + 相关测试**

Run: `cd server && ./mvnw -q -Dtest=MessageSourcesPersistenceTest,ConversationServiceTest,ConversationStoreTest test`
Expected: 全通过（计数无 Failures/Errors）。

- [ ] **Step 10: Commit**

```bash
git add server/src/main/java/com/hify/conversation/dto/MessageSource.java \
        server/src/main/resources/db/migration/V20__message_add_sources.sql \
        server/src/main/java/com/hify/conversation/config/MessageSourcesTypeHandler.java \
        server/src/main/java/com/hify/conversation/entity/Message.java \
        server/src/main/java/com/hify/conversation/service/ConversationStore.java \
        server/src/main/java/com/hify/conversation/service/ConversationService.java \
        server/src/test/java/com/hify/conversation/service/MessageSourcesPersistenceTest.java \
        server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java \
        server/src/test/java/com/hify/conversation/service/ConversationStoreTest.java
git commit -m "feat(conversation): message 加 sources jsonb 列与持久化地基（V20+TypeHandler，TDD）"
```

---

### Task 2: 读路径暴露来源 — `MessageView.sources` + `toView` + history/send 响应

**Files:**
- Modify: `server/src/main/java/com/hify/conversation/dto/MessageView.java`
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java:185-188`（`toView`）
- Test: `server/src/test/java/com/hify/conversation/controller/ConversationControllerTest.java`（history 切片带 sources 序列化）

**Interfaces:**
- Consumes: `Message.getSources()`（Task 1）
- Produces: `MessageView` 末尾字段 `List<MessageSource> sources`；`ConversationService.toView(Message)` 填充 `m.getSources()`

- [ ] **Step 1: 写失败的序列化测试**

在 `ConversationControllerTest.java` 增用例（沿用该类既有 MockMvc + mock service 风格；断言 history 返回体含 sources 且 Long→string、score 为 number）：

```java
    @Test
    void history_returns_sources_with_long_as_string_and_score_as_number() throws Exception {
        var view = new com.hify.conversation.dto.MessageView(
                7L, "assistant", "答案", 1, 2, java.time.OffsetDateTime.now(),
                java.util.List.of(new com.hify.conversation.dto.MessageSource(
                        10L, 20L, "手册.pdf", 0.82, "预览文字")));
        org.mockito.Mockito.when(conversationService.history(org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(view));

        mockMvc.perform(get("/api/v1/conversation/messages").param("conversationId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sources[0].chunkId").value("10"))   // Long → string
                .andExpect(jsonPath("$.data[0].sources[0].documentName").value("手册.pdf"))
                .andExpect(jsonPath("$.data[0].sources[0].score").value(0.82))     // double → number
                .andExpect(jsonPath("$.data[0].sources[0].preview").value("预览文字"));
    }
```

> 若该测试类里 mock service 的字段名/`get`/`status`/`jsonPath` 静态导入名不同，按类内既有用例对齐。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && ./mvnw -q -Dtest=ConversationControllerTest#history_returns_sources_with_long_as_string_and_score_as_number test`
Expected: 编译失败（`MessageView` 尚无第 7 个组件 `sources`）。

- [ ] **Step 3: `MessageView` 加 `sources` 字段**

改 `MessageView.java` 为：

```java
package com.hify.conversation.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 消息视图（成员族响应）。id/token 为 Long/Integer（Long→string、Integer 保持 number）。
 * sources：引用来源快照数组，未绑库/降级/无命中为空数组（非 null）。
 */
public record MessageView(
        Long id,
        String role,
        String content,
        Integer promptTokens,
        Integer completionTokens,
        OffsetDateTime createTime,
        List<MessageSource> sources) {
}
```

- [ ] **Step 4: `toView` 填充 sources**

改 `ConversationService.toView`（第 185-188 行）：

```java
    private static MessageView toView(Message m) {
        return new MessageView(m.getId(), m.getRole(), m.getContent(),
                m.getPromptTokens(), m.getCompletionTokens(), m.getCreateTime(),
                m.getSources() == null ? java.util.List.of() : m.getSources());
    }
```

- [ ] **Step 5: 跑测试确认通过 + 编译全绿**

Run: `cd server && ./mvnw -q -Dtest=ConversationControllerTest test`
Expected: 全通过。（`MessageView` 构造点若别处还有——用 `grep -rn "new MessageView(" server/src`——补 `List.of()` 参数。）

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/com/hify/conversation/dto/MessageView.java \
        server/src/main/java/com/hify/conversation/service/ConversationService.java \
        server/src/test/java/com/hify/conversation/controller/ConversationControllerTest.java
git commit -m "feat(conversation): MessageView 带 sources，history/send 响应暴露引用来源（TDD）"
```

---

### Task 3: 检索产出来源 — `augmentWithKnowledge` 返回 `Augmented(prompt, sources)` + 预览截断配置 + 落库

**Files:**
- Modify: `server/src/main/java/com/hify/conversation/config/ConversationProperties.java`
- Modify: `server/src/main/resources/application.yml:126-132`
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java`（构造器注入 props、`augmentWithKnowledge` 改造、两处调用换真值）
- Test: `server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java`

**Interfaces:**
- Consumes: `ConversationStore.appendAssistant(..., List<MessageSource>)`（Task 1）；`KnowledgeFacade.retrieve(List<Long>, String) : List<RetrievedChunk>`
- Produces: `ConversationService.Augmented(String prompt, List<MessageSource> sources)`（private record）；`ConversationProperties.sourcePreviewLength() : int`

- [ ] **Step 1: 写失败的分支测试（在 `ConversationServiceTest`）**

用 `ArgumentCaptor<List<MessageSource>>` 捕获 `appendAssistant` 第 8 参。追加用例：

```java
    @Test
    void send_boundApp_hit_capturesSourcesWithTruncatedPreview() {
        // app 绑库、检索命中一段 40 字内容；preview 截断长度取自 props（测试用 10）
        var app = runnableChatAppBoundTo(List.of(100L));            // helper：datasetIds=[100]
        when(appFacade.findRunnableChatApp(1L)).thenReturn(Optional.of(app));
        when(knowledgeFacade.retrieve(eq(List.of(100L)), eq("问题")))
                .thenReturn(List.of(new RetrievedChunk(10L, 20L, "手册.pdf", "0123456789ABCDEF", 0.9)));
        // store/chatInvoker 常规桩（沿用 setUp 已建的）...

        service.send(1L, null, "问题", member);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSource>> cap = ArgumentCaptor.forClass(List.class);
        verify(store).appendAssistant(anyLong(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(), cap.capture());
        List<MessageSource> src = cap.getValue();
        assertEquals(1, src.size());
        assertEquals(10L, src.get(0).chunkId());
        assertEquals("手册.pdf", src.get(0).documentName());
        assertEquals(0.9, src.get(0).score());
        assertEquals("0123456789", src.get(0).preview());   // 截前 10 字（props 配置）
    }

    @Test
    void send_unboundApp_capturesEmptySources() {
        var app = runnableChatAppBoundTo(List.of());                 // datasetIds 空
        when(appFacade.findRunnableChatApp(1L)).thenReturn(Optional.of(app));

        service.send(1L, null, "问题", member);

        verify(store).appendAssistant(anyLong(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(),
                eq(List.of()));
        verifyNoInteractions(knowledgeFacade);
    }

    @Test
    void send_retrieveThrows_degradesWithEmptySources() {
        var app = runnableChatAppBoundTo(List.of(100L));
        when(appFacade.findRunnableChatApp(1L)).thenReturn(Optional.of(app));
        when(knowledgeFacade.retrieve(any(), any())).thenThrow(new RuntimeException("embedding 未配"));

        service.send(1L, null, "问题", member);   // 不抛，降级

        verify(store).appendAssistant(anyLong(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(),
                eq(List.of()));
    }

    @Test
    void send_hitEmpty_capturesEmptySources() {
        var app = runnableChatAppBoundTo(List.of(100L));
        when(appFacade.findRunnableChatApp(1L)).thenReturn(Optional.of(app));
        when(knowledgeFacade.retrieve(any(), any())).thenReturn(List.of());

        service.send(1L, null, "问题", member);

        verify(store).appendAssistant(anyLong(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong(),
                eq(List.of()));
    }
```

> `runnableChatAppBoundTo(List<Long>)` 是本测试类内的私有 helper——按类里既有构造 `AppRuntimeView` 的方式写（现有 setUp 已构造过 app，复制其字段，只改 datasetIds）。`service` 需在 setUp 里用带 props 的构造器新建，props 的 `sourcePreviewLength=10`。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && ./mvnw -q -Dtest=ConversationServiceTest test`
Expected: 编译失败（`ConversationProperties` 无 `sourcePreviewLength`、`service` 构造器签名未变）。

- [ ] **Step 3: `ConversationProperties` 加 `sourcePreviewLength`**

改 `ConversationProperties.java`:

```java
package com.hify.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * conversation 模块配置（hify.conversation.*）。
 * memory.window-rounds：滑动窗口轮数 N。list.recent-limit：侧边栏最近会话上限。
 * source-preview-length：引用来源卡片预览截断字数（不存全文，database/前端展示用）。
 */
@ConfigurationProperties(prefix = "hify.conversation")
public record ConversationProperties(Memory memory, ListProps list, int sourcePreviewLength) {

    public record Memory(int windowRounds) {
    }

    public record ListProps(int recentLimit) {
    }
}
```

- [ ] **Step 4: application.yml 加配置键**

改 `application.yml` 的 `conversation:` 段（第 126 行起），在 `list:` 后加：

```yaml
  conversation:
    memory:
      window-rounds: ${HIFY_CONVERSATION_WINDOW_ROUNDS:10}
    list:
      recent-limit: ${HIFY_CONVERSATION_LIST_LIMIT:50}
    # 引用来源卡片的预览截断字数（不存命中段全文，看全文走命中测试弹窗）。
    source-preview-length: ${HIFY_CONVERSATION_SOURCE_PREVIEW_LENGTH:120}
```

- [ ] **Step 5: `ConversationService` 注入 props + 改造 `augmentWithKnowledge` + 换真值**

1. 构造器加参数 `ConversationProperties props` 并存字段（放在现有字段区、构造器末尾赋值）。
2. 新增 private record 与改造后的方法；两处调用换用 `Augmented`：

```java
    /** 检索产出：注入后的提示词 + 来源快照列表（未绑/降级/命中空时 sources 为空）。 */
    private record Augmented(String prompt, java.util.List<MessageSource> sources) {}

    private Augmented augmentWithKnowledge(AppRuntimeView app, String content) {
        if (app.datasetIds() == null || app.datasetIds().isEmpty()) {
            return new Augmented(app.systemPrompt(), java.util.List.of());
        }
        try {
            List<RetrievedChunk> chunks = knowledgeFacade.retrieve(app.datasetIds(), content);
            if (chunks.isEmpty()) {
                return new Augmented(app.systemPrompt(), java.util.List.of());
            }
            StringBuilder sb = new StringBuilder();
            if (StringUtils.hasText(app.systemPrompt())) {
                sb.append(app.systemPrompt()).append("\n\n");
            }
            sb.append(KNOWLEDGE_PROMPT_HEADER);
            List<MessageSource> sources = new java.util.ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                RetrievedChunk c = chunks.get(i);
                sb.append('\n').append('[').append(i + 1).append("] ").append(c.content());
                sources.add(new MessageSource(c.chunkId(), c.documentId(), c.documentName(),
                        c.score(), preview(c.content())));
            }
            return new Augmented(sb.toString(), List.copyOf(sources));
        } catch (Exception e) {
            log.warn("知识检索失败，本轮降级为无参考资料回答 appId={}", app.appId(), e);
            return new Augmented(app.systemPrompt(), java.util.List.of());
        }
    }

    /** 截断命中段为预览（不存全文）。长度取全局配置，按字符（code point 无关，中文按 char）截断。 */
    private String preview(String content) {
        int len = props.sourcePreviewLength();
        if (content == null) return "";
        return content.length() <= len ? content : content.substring(0, len);
    }
```

3. `send`（第 72-76 行区）：
```java
            Augmented aug = augmentWithKnowledge(app, content);
            LlmReply reply = chatInvoker.invoke(chatClient, aug.prompt(), turn.window());
            Message saved = store.appendAssistant(cid, reply.content(), reply.promptTokens(), reply.completionTokens(),
                    current.userId(), appId, app.modelId(), aug.sources());
```

4. `sendStream`（第 96 行 `augmentWithKnowledge` 与第 116-120 行 done）：
```java
        Augmented aug = augmentWithKnowledge(app, content);
        // ... deltas 用 aug.prompt()：invokeStream(chatClient, aug.prompt(), turn.window())
        Mono<StreamEvent> done = Mono.<StreamEvent>fromCallable(() -> {
            Message saved = store.appendAssistant(cid, buf.toString(), usage[0], usage[1],
                    current.userId(), appId, app.modelId(), aug.sources());
            return new StreamEvent.Done(cid, saved.getId(), usage[0], usage[1]);
        }).subscribeOn(Schedulers.boundedElastic());
```
（`aug` 供 Task 4 的 Sources 事件复用，故声明在 flux 组装前。）

- [ ] **Step 6: setUp 用带 props 的构造器新建 service**

在 `ConversationServiceTest.setUp()` 里，`service = new ConversationService(appFacade, providerFacade, chatInvoker, store, quotaGuard, knowledgeFacade, new ConversationProperties(new ConversationProperties.Memory(10), new ConversationProperties.ListProps(50), 10));`（`sourcePreviewLength=10` 对齐 Step 1 断言）。导入 `ConversationProperties`。

- [ ] **Step 7: 跑测试确认通过**

Run: `cd server && ./mvnw -q -Dtest=ConversationServiceTest test`
Expected: 全通过（四分支 + 既有用例）。

- [ ] **Step 8: Commit**

```bash
git add server/src/main/java/com/hify/conversation/config/ConversationProperties.java \
        server/src/main/resources/application.yml \
        server/src/main/java/com/hify/conversation/service/ConversationService.java \
        server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java
git commit -m "feat(conversation): 检索命中映射为来源快照并落库，预览截断长度外化配置（TDD）"
```

---

### Task 4: SSE `sources` 事件（只增不改）

**Files:**
- Modify: `server/src/main/java/com/hify/conversation/service/StreamEvent.java`
- Modify: `server/src/main/java/com/hify/conversation/dto/StreamPayloads.java`
- Modify: `server/src/main/java/com/hify/conversation/controller/ConversationController.java:91-102`（`toSse` 加分支）
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java:122`（flux concat 插入 Sources）
- Test: `server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java`（流事件顺序）+ `ConversationControllerTest.java`（事件名映射）

**Interfaces:**
- Consumes: `ConversationService.Augmented.sources()`（Task 3）
- Produces: `StreamEvent.Sources(List<MessageSource> sources)`；`StreamPayloads.Sources(List<MessageSource> sources)`；SSE `event: sources`

- [ ] **Step 1: 写失败的流顺序测试（`ConversationServiceTest`）**

```java
    @Test
    void sendStream_boundHit_emitsSourcesAfterMetaBeforeDelta() {
        var app = runnableChatAppBoundTo(List.of(100L));
        when(appFacade.findRunnableChatApp(1L)).thenReturn(Optional.of(app));
        when(knowledgeFacade.retrieve(any(), any()))
                .thenReturn(List.of(new RetrievedChunk(10L, 20L, "手册.pdf", "命中内容", 0.9)));
        // chatInvoker.invokeStream 桩：吐一个 delta（按类内既有 sendStream 测试的桩法）
        stubStreamOneDelta("你好");   // helper：见类内既有流测试

        List<StreamEvent> events = service.sendStream(1L, null, "问题", member).collectList().block();

        assertTrue(events.get(0) instanceof StreamEvent.Meta);
        assertTrue(events.get(1) instanceof StreamEvent.Sources);
        assertEquals(1, ((StreamEvent.Sources) events.get(1)).sources().size());
        assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.Delta));
        // Sources 在首个 Delta 之前
        int srcIdx = indexOfType(events, StreamEvent.Sources.class);
        int deltaIdx = indexOfType(events, StreamEvent.Delta.class);
        assertTrue(srcIdx < deltaIdx);
    }

    @Test
    void sendStream_unbound_emitsNoSourcesEvent() {
        var app = runnableChatAppBoundTo(List.of());
        when(appFacade.findRunnableChatApp(1L)).thenReturn(Optional.of(app));
        stubStreamOneDelta("你好");

        List<StreamEvent> events = service.sendStream(1L, null, "问题", member).collectList().block();

        assertTrue(events.stream().noneMatch(e -> e instanceof StreamEvent.Sources));
    }
```

> `stubStreamOneDelta`/`indexOfType` 按类内既有流测试的辅助写法补；若已有等价 helper 直接复用。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && ./mvnw -q -Dtest=ConversationServiceTest test`
Expected: 编译失败（`StreamEvent.Sources` 不存在）。

- [ ] **Step 3: `StreamEvent` 加 `Sources`**

改 `StreamEvent.java`：

```java
package com.hify.conversation.service;

import com.hify.conversation.dto.MessageSource;
import java.util.List;

/** 流式编排的内部事件（仅 service↔controller，不跨模块）。 */
public sealed interface StreamEvent
        permits StreamEvent.Meta, StreamEvent.Sources, StreamEvent.Delta, StreamEvent.Done {

    /** 开场元信息（D2）：首个 delta 前发出，前端立即拿到会话 id。 */
    record Meta(Long conversationId) implements StreamEvent {}

    /** 引用来源（本轮）：Meta 后、首个 Delta 前发；命中为空则不发此事件。 */
    record Sources(List<MessageSource> sources) implements StreamEvent {}

    /** 一段增量正文。 */
    record Delta(String text) implements StreamEvent {}

    /** 流正常结束、assistant 已落库的终态。 */
    record Done(Long conversationId, Long messageId, int promptTokens, int completionTokens)
            implements StreamEvent {}
}
```

- [ ] **Step 4: `StreamPayloads` 加 `Sources`**

在 `StreamPayloads.java` 加一行 record（`Meta` 之后）：

```java
    public record Sources(java.util.List<com.hify.conversation.dto.MessageSource> sources) {}
```

- [ ] **Step 5: `sendStream` flux 插入 Sources 事件**

改 `ConversationService.sendStream` 的 `Flux.concat`（第 122 行）为：

```java
        Flux<StreamEvent> sourcesFlux = aug.sources().isEmpty()
                ? Flux.empty()
                : Flux.just(new StreamEvent.Sources(aug.sources()));
        return Flux.concat(Mono.<StreamEvent>just(new StreamEvent.Meta(cid)), sourcesFlux, deltas, done)
                .onErrorResume(err -> Mono.fromRunnable(() ->
                                store.cleanupFailedTurn(turn.conversationId(), turn.userMessageId(), turn.newConversation()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(cleanupEx -> Mono.empty())
                        .then(Mono.<StreamEvent>error(err)));
```

- [ ] **Step 6: controller `toSse` 加 `sources` 分支**

在 `ConversationController.toSse`（第 91-102 行）`Meta` 分支后加：

```java
        } else if (e instanceof StreamEvent.Sources s) {
            return sse("sources", new StreamPayloads.Sources(s.sources()));
```

> `takeUntil` 只在 `done`/`error` 收尾，`sources` 不是终态，不影响流继续。

- [ ] **Step 7: controller 切片测试事件名**

在 `ConversationControllerTest` 加用例：mock `conversationService.sendStream` 返回 `Flux.just(new StreamEvent.Meta(9L), new StreamEvent.Sources(List.of(new MessageSource(10L,20L,"手册.pdf",0.82,"预览"))), new StreamEvent.Done(9L,7L,1,2))`，对 `/api/v1/conversation/messages/stream` 断言响应体含 `event:sources` 且其 data 里 `chunkId` 为字符串 `"10"`、`score` 为 `0.82`。（按该类既有 SSE 测试的读流断言手法写；若无，用 `webTestClient`/`MockMvc` asyncDispatch 既有范式。）

- [ ] **Step 8: 跑测试确认通过**

Run: `cd server && ./mvnw -q -Dtest=ConversationServiceTest,ConversationControllerTest test`
Expected: 全通过。

- [ ] **Step 9: Commit**

```bash
git add server/src/main/java/com/hify/conversation/service/StreamEvent.java \
        server/src/main/java/com/hify/conversation/dto/StreamPayloads.java \
        server/src/main/java/com/hify/conversation/controller/ConversationController.java \
        server/src/main/java/com/hify/conversation/service/ConversationService.java \
        server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java \
        server/src/test/java/com/hify/conversation/controller/ConversationControllerTest.java
git commit -m "feat(conversation): 新增 SSE sources 事件（Meta 后 Delta 前，空则不发，只增不改，TDD）"
```

- [ ] **Step 10: 后端全量回归（含 Modulith/ArchUnit）**

Run: `cd server && ./mvnw -q test`
Expected: 全绿（无 Failures/Errors）。ArchUnit：`MessageSource` 在 `conversation.dto`、不 import entity、无新增跨模块依赖，应零修改通过。若 ArchUnit 报 DTO→entity，检查 `MessageSource` 是否误引 entity。

---

### Task 5: 前端数据层 — 类型 + `useChatStream` onSources + store 挂载

**Files:**
- Modify: `web/src/types/conversation.ts`
- Modify: `web/src/composables/useChatStream.ts`
- Modify: `web/src/stores/conversation.ts`
- Test: `web/src/composables/__tests__/useChatStream.spec.ts`, `web/src/stores/__tests__/conversation.spec.ts`

**Interfaces:**
- Produces: `MessageSource` 接口；`MessageView.sources?: MessageSource[]`；`ChatStreamHandlers.onSources?(sources: MessageSource[])`；store `send` 占位消息带 `sources: []` 并在 onSources 时填充

- [ ] **Step 1: 写失败的 useChatStream 测试**

在 `useChatStream.spec.ts` 加用例（沿用该文件已有的「构造 SSE 块喂 mock reader」手法）：

```ts
it('parses sources event and calls onSources', async () => {
  const onSources = vi.fn()
  // 构造事件块：event: sources\ndata: {"sources":[...]}\n\n（用文件里既有的 mock fetch/reader helper）
  mockSseStream([
    'event: meta\ndata: {"conversationId":"9"}\n\n',
    'event: sources\ndata: {"sources":[{"chunkId":"10","documentId":"20","documentName":"手册.pdf","score":0.82,"preview":"预览"}]}\n\n',
    'event: message\ndata: {"delta":"你好"}\n\n',
    'event: done\ndata: {"conversationId":"9","messageId":"7","usage":{"promptTokens":1,"completionTokens":2}}\n\n',
  ])
  const { start } = useChatStream()
  await start('1', null, '问题', {
    onDelta: vi.fn(), onDone: vi.fn(), onError: vi.fn(), onSources,
  })
  expect(onSources).toHaveBeenCalledWith([
    { chunkId: '10', documentId: '20', documentName: '手册.pdf', score: 0.82, preview: '预览' },
  ])
})
```

> `mockSseStream` 用文件里已有的 fetch/reader mock；若命名不同按现有用例照搬。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web && pnpm vitest run src/composables/__tests__/useChatStream.spec.ts`
Expected: FAIL（`onSources` 未被调用）。

- [ ] **Step 3: 类型加 `MessageSource`**

在 `web/src/types/conversation.ts` 加接口并给 `MessageView` 加可选字段（mirror 既有 `error?`）：

```ts
/** 引用来源快照（对齐后端 MessageSource）。id 类为 string（Long）；score 为 number（0~1）。 */
export interface MessageSource {
  chunkId: string
  documentId: string
  documentName: string
  score: number
  preview: string
}
```

在 `MessageView` 接口里 `error?` 附近加：

```ts
  /** 引用来源；未绑库/降级/无命中为空数组或缺省。 */
  sources?: MessageSource[]
```

- [ ] **Step 4: `useChatStream` 加 onSources**

在 `ChatStreamHandlers` 加：

```ts
  /** 引用来源事件（Meta 后、首个 delta 前）；命中为空时后端不发本事件 */
  onSources?: (sources: import('@/types/conversation').MessageSource[]) => void
```

在 `dispatch` 的分支链里，`meta` 之后加：

```ts
    else if (event === 'sources') h.onSources?.(payload.sources)
```

- [ ] **Step 5: 跑 useChatStream 测试确认通过**

Run: `cd web && pnpm vitest run src/composables/__tests__/useChatStream.spec.ts`
Expected: PASS。

- [ ] **Step 6: 写失败的 store 测试**

在 `conversation.spec.ts` 加用例（沿用该文件 mock `useChatStream` 的手法，触发 onSources 回调后断言助手消息挂上 sources）：

```ts
it('attaches sources from onSources to the assistant message', async () => {
  // 安排 chat.start 的 mock：依次触发 onMeta、onSources、onDelta、onDone（见文件既有 mock 手法）
  const sources = [{ chunkId: '10', documentId: '20', documentName: '手册.pdf', score: 0.82, preview: '预览' }]
  mockChatStart({ meta: '9', sources, delta: '你好', done: { conversationId: '9', messageId: '7', usage: { promptTokens: 1, completionTokens: 2 } } })
  const store = useConversationStore()
  await store.send('1', '问题')
  const asst = store.messages.find((m) => m.role === 'assistant')!
  expect(asst.sources).toEqual(sources)
})
```

> `mockChatStart` 表意即「让 mock 的 chat.start 依次回调 handlers」；按文件里既有 mock 写法实现（很可能是 `vi.mocked(chat.start).mockImplementation(async (_a,_c,_ct,h)=>{ h.onMeta?.(...); h.onSources?.(sources); h.onDelta('你好'); h.onDone(...) })`）。

- [ ] **Step 7: 跑测试确认失败**

Run: `cd web && pnpm vitest run src/stores/__tests__/conversation.spec.ts`
Expected: FAIL（`asst.sources` 为 undefined）。

- [ ] **Step 8: store 占位消息带 `sources: []` + onSources 填充**

在 `stores/conversation.ts` `send()`：
1. 助手占位对象加 `sources: []`（第 88-91 行的 push 对象里）。
2. `chat.start(...)` handlers 里，`onMeta` 之后加：

```ts
        onSources: (list) => { messages.value[idx].sources = list },
```

（`import type { MessageSource }` 若类型检查需要则补；`list` 已由 handler 类型推断。）

- [ ] **Step 9: 跑 store 测试确认通过**

Run: `cd web && pnpm vitest run src/stores/__tests__/conversation.spec.ts`
Expected: PASS。（重载路径 `loadMessages` 无需改：`getMessages` 已返回带 sources 的 `MessageView`，类型已含 `sources?`。）

- [ ] **Step 10: Commit**

```bash
git add web/src/types/conversation.ts web/src/composables/useChatStream.ts web/src/stores/conversation.ts \
        web/src/composables/__tests__/useChatStream.spec.ts web/src/stores/__tests__/conversation.spec.ts
git commit -m "feat(web): useChatStream 解析 sources 事件、store 把来源挂到助手消息（TDD）"
```

---

### Task 6: 前端展示 — ChatView 折叠来源卡片

**Files:**
- Modify: `web/src/views/conversation/ChatView.vue`（模板第 148-150 行区 + `<script setup>` 导入图标/组件 + 样式）
- Test: `web/src/views/conversation/__tests__/ChatView.spec.ts`

**Interfaces:**
- Consumes: `MessageView.sources?: MessageSource[]`（Task 5）

- [ ] **Step 1: 写失败的 ChatView 测试**

在 `ChatView.spec.ts` 加用例（沿用该文件 mount + store 注入 messages 的手法）：

```ts
it('renders collapsible source cards for assistant message with sources', async () => {
  // 安排 store.messages 含一条 assistant 消息，sources 两条（见文件既有注入手法）
  const wrapper = mountChatViewWithMessages([
    { id: '7', role: 'assistant', content: '答案', promptTokens: 1, completionTokens: 2, createTime: 't',
      sources: [
        { chunkId: '10', documentId: '20', documentName: '手册.pdf', score: 0.82, preview: '预览A' },
        { chunkId: '11', documentId: '21', documentName: 'FAQ.docx', score: 0.71, preview: '预览B' },
      ] },
  ])
  const block = wrapper.find('[data-test="msg-sources"]')
  expect(block.exists()).toBe(true)
  expect(block.text()).toContain('参考来源 (2)')
  const cards = wrapper.findAll('[data-test="source-card"]')
  expect(cards).toHaveLength(2)
  expect(cards[0].text()).toContain('手册.pdf')
  expect(cards[0].text()).toContain('82%')       // score*100 取整
  expect(cards[0].text()).toContain('预览A')
})

it('does not render source block when no sources', () => {
  const wrapper = mountChatViewWithMessages([
    { id: '8', role: 'assistant', content: '答案', promptTokens: 1, completionTokens: 2, createTime: 't', sources: [] },
  ])
  expect(wrapper.find('[data-test="msg-sources"]').exists()).toBe(false)
})
```

> `mountChatViewWithMessages` 表意「mount ChatView 并让 store.messages = 给定数组」；按文件既有 mount+mock store 手法实现。Element Plus `el-collapse` 在测试里默认收起——用例断言的是 DOM 存在与文本，若展开态才渲染卡片，测试里 `await` 触发 `el-collapse-item` 展开或用 `find` 断标题即可；实现用「标题始终渲染、卡片在 collapse-item 内」，测试对卡片断言前先点开标题（`await block.find('.el-collapse-item__header').trigger('click')`）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ChatView.spec.ts`
Expected: FAIL（无 `msg-sources` 节点）。

- [ ] **Step 3: 模板加折叠来源区**

在 `ChatView.vue` 模板 `<div v-if="m.error" ...>` 之后（第 149 行后、气泡 `</div>` 前，即第 150 行处）加：

```html
            <div
              v-if="m.role === 'assistant' && m.sources && m.sources.length"
              class="chat__sources"
              data-test="msg-sources"
            >
              <el-collapse>
                <el-collapse-item :name="m.id">
                  <template #title>
                    <span class="chat__sources-title">📎 参考来源 ({{ m.sources.length }})</span>
                  </template>
                  <div
                    v-for="s in m.sources"
                    :key="s.chunkId"
                    class="chat__source-card"
                    data-test="source-card"
                  >
                    <div class="chat__source-head">
                      <span class="chat__source-doc">📄 {{ s.documentName }}</span>
                      <el-tag size="small" type="info">{{ Math.round(s.score * 100) }}%</el-tag>
                    </div>
                    <div class="chat__source-preview">{{ s.preview }}</div>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </div>
```

（`el-collapse`/`el-collapse-item`/`el-tag` 为 Element Plus 全局组件，无需额外 import；`Math` 模板可直接用。）

- [ ] **Step 4: 加样式**

在 `ChatView.vue` `<style>` 里 `&__bubble-error` 附近加（scss 嵌套按文件既有风格）：

```scss
  &__sources {
    margin-top: 6px;
  }
  &__sources-title {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
  &__source-card {
    padding: 8px 10px;
    margin-bottom: 6px;
    background: var(--el-fill-color-light);
    border-radius: 6px;
  }
  &__source-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-bottom: 4px;
  }
  &__source-doc {
    font-size: 13px;
    font-weight: 500;
  }
  &__source-preview {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    line-height: 1.5;
  }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ChatView.spec.ts`
Expected: PASS。

- [ ] **Step 6: 前端全量 + 构建**

Run: `cd web && pnpm vitest run && pnpm build`
Expected: 全部测试绿、`tsc` 无类型错误、build 成功。

- [ ] **Step 7: Commit**

```bash
git add web/src/views/conversation/ChatView.vue web/src/views/conversation/__tests__/ChatView.spec.ts
git commit -m "feat(web): 助手气泡下方折叠展示参考来源卡片（文档名+分数+预览，TDD）"
```

---

### Task 7: 文档入档 + 自检

**Files:**
- Modify: `docs/architecture/data-model.md`（message 表字段补 `sources jsonb`）
- Modify: `docs/self-check.md`（追加本轮自检）

- [ ] **Step 1: data-model.md 补字段**

在 `data-model.md` 的 message 表字段清单里，加一行说明 `sources jsonb`（引用来源快照数组 `[{chunkId,documentId,documentName,score,preview}]`，随消息落库、会话删级联、未命中为 `[]`）。按文件既有表格/清单格式对齐。

- [ ] **Step 2: 追加自检到 docs/self-check.md**

在 `docs/self-check.md` 末尾追加本轮小结：做了什么（sources 列/TypeHandler/Augmented/SSE sources 事件/前端折叠卡片）、验证了什么（后端 `mvnw test` 全绿含连库往返、前端 vitest+build 全绿）、留账（无新留账；卡片点击/全文/计量按 spec「不做」延后）。

- [ ] **Step 3: Commit**

```bash
git add docs/architecture/data-model.md docs/self-check.md
git commit -m "docs: message.sources 入档 data-model + 本轮自检"
```

---

## 手动验收（合并前，spec §8.4）

建知识库传文档（ready）→ 应用绑库 → 聊天问文档内问题：① 流式回答时「📎 参考来源 (N)」折叠区出现，展开见文档名/分数/预览；② 刷新页面重载历史，来源仍在；③ 问无关问题（回答正常、无来源折叠区）；④ 停用 embedding 模型后聊天（降级照常答、无来源折叠区、server 日志有 warn）；⑤ member 账号全流程同权限口径。

---

## Self-Review（写完对照 spec）

**Spec 覆盖**：§1 数据形状→Task 1；§2 V20→Task 1；§3 持久化 TypeHandler/实体/appendAssistant→Task 1；§4 编排 Augmented/四分支/两路径落库→Task 3；§5 SSE Sources 事件+MessageView.sources→Task 2(MessageView)+Task 4(事件)；§6 前端类型/useChatStream/store/ChatView→Task 5+6；§7 配置键→Task 3；§8 测试三层→各 Task 的测试步 + Task 1 连库往返；§9 约束→Global Constraints + Task 4 Step 10 回归；§10 文档→Task 7。全部有归属。

**占位扫描**：无 TBD/TODO；所有代码步给了完整代码。少数「按类内既有 helper 写」处（`runnableChatAppBoundTo`/`stubStreamOneDelta`/`mockSseStream`/`mockChatStart`/`mountChatViewWithMessages`）是复用现有测试脚手架的显式指引，非省略实现——执行时以对应测试文件既有手法为准。

**类型一致**：`MessageSource(Long,Long,String,double,String)` 前后端一致（前端 id→string、score→number）；`appendAssistant` 八参签名在 Task 1 定义、Task 3 复用一致；`Augmented(prompt,sources)` Task 3 定义、Task 4 复用；`StreamEvent.Sources(List<MessageSource>)` / `StreamPayloads.Sources` / SSE `event:sources` / 前端 `onSources` / `payload.sources` 命名贯通一致。
