# conversation 多轮记忆 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让对话应用记住同一会话的历史（滑动窗口进 prompt），并让前端能管理多个会话（列表/新建/切换/刷新恢复）。

**Architecture:** 后端在既有单轮链路上叠加：`openTurn` 事务内读最近 `2N+1` 条窗口随 `cid` 一起返回，`ChatInvoker` 退化为「领域消息列表 → Spring AI 消息列表」映射器，编排层把窗口喂模型——LLM 调用仍夹在两事务之间、无事务包裹。新增不分页的会话列表端点。前端用 URL query 存当前会话 id、新建 Pinia store 持运行态、ChatView 拆左右两栏。

**Tech Stack:** Spring Boot 3 + Spring AI 1.0.1 + MyBatis-Plus + PostgreSQL；Vue 3 `<script setup>` + Pinia + vue-router + Element Plus；JUnit5 + Mockito（后端）/ vitest + @vue/test-utils（前端）。

## Global Constraints

- 滑动窗口轮数默认 **N=10**（`hify.conversation.memory.window-rounds`）；store 读最近 `2*N+1` 条消息。
- 会话列表上限默认 **50**（`hify.conversation.list.recent-limit`），**不分页**，按 `update_time desc`。
- 错误码 **17xxx 段不增不改**；复用 17001 / 10005(NOT_FOUND) / 10001(VALIDATION)。
- `ConversationService` **类与方法均无 `@Transactional`**；`@Transactional` 全部收口在 `ConversationStore`；LLM 调用（`chatInvoker.invoke`）必须在两事务之间、不被任何事务包裹。
- Long 一律由 infra 全局 Jackson 序列化为 **string**（DTO 用 Long/Integer 原类型即可）。
- 成员族路由 `/api/v1/conversation/**`；既有 messages 对外 URL 不变。
- `@TableLogic`（`BaseEntity.deleted`）自动给所有查询追加 `where deleted=false`，**不手写**该条件。
- 数据库表结构**不变**，本轮无新 Flyway 脚本。
- 测试放 `__tests__/`（前端）/ 同包 `src/test`（后端）；判 mvn 结果看 Surefire `Tests run/Failures/Errors`，**不 grep BUILD SUCCESS**。
- 跨模块 DTO 走 api 顶层包——本轮无新增跨模块 DTO（`ConversationView` 为模块内 DTO）。

**命令速查**
- 后端单类测试：`cd server && mvn test -Dtest=<ClassName>`（看 `Tests run: X, Failures: 0, Errors: 0`）
- 后端全量：`cd server && mvn test`
- 前端单文件：`cd web && pnpm vitest run <path>`
- 前端全量 + 类型：`cd web && pnpm test && pnpm typecheck`

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `conversation/config/ConversationProperties.java` | 模块配置（window-rounds / recent-limit） | 新增 |
| `conversation/config/ConversationConfig.java` | 启用 @ConfigurationProperties | 新增 |
| `conversation/service/TurnContext.java` | openTurn 结果（cid + 窗口） | 新增 |
| `conversation/service/ChatInvoker.java` | 领域消息→Spring AI 消息映射 + 调用 | 改 |
| `conversation/service/ConversationStore.java` | 落库/读取 + 窗口 + 会话列表（事务收口） | 改 |
| `conversation/service/ConversationService.java` | 编排（无事务） | 改 |
| `conversation/dto/ConversationView.java` | 会话列表项视图 | 新增 |
| `conversation/controller/ConversationController.java` | 路由（messages + conversations） | 改 |
| `server/.../application.yml` | hify.conversation.* 配置 | 改 |
| `web/src/types/conversation.ts` | ConversationView 类型 | 改 |
| `web/src/api/conversation.ts` | listConversations | 改 |
| `web/src/stores/conversation.ts` | 会话运行态 store | 新增 |
| `web/src/views/conversation/ConversationSidebar.vue` | 侧边栏列表组件（本页局部） | 新增 |
| `web/src/views/conversation/ChatView.vue` | 两栏聊天页（URL query + store） | 改 |

后端测试：`ChatInvokerTest`(新)、`ConversationStoreTest`(改)、`ConversationServiceTest`(改)、`ConversationControllerTest`(改)。
前端测试：`api/__tests__/conversation.spec.ts`(改)、`stores/__tests__/conversation.spec.ts`(新)、`views/conversation/__tests__/ConversationSidebar.spec.ts`(新)、`views/conversation/__tests__/ChatView.spec.ts`(改)。

---

### Task 1: ChatInvoker.toMessages 纯映射方法

把「领域消息窗口 → Spring AI 消息列表」抽成纯方法并单测。**纯新增**，不改 `invoke` 旧签名，编译保持绿（`invoke` 在 Task 2 改）。

**Files:**
- Modify: `server/src/main/java/com/hify/conversation/service/ChatInvoker.java`
- Test: `server/src/test/java/com/hify/conversation/service/ChatInvokerTest.java`（新建）

**Interfaces:**
- Produces: `List<org.springframework.ai.chat.messages.Message> ChatInvoker.toMessages(String systemPrompt, List<com.hify.conversation.entity.Message> window)` —— 包级可见；systemPrompt 有文本则首位 `SystemMessage`，其后按窗口顺序 `user→UserMessage / assistant→AssistantMessage`，末位即当前消息。

- [ ] **Step 1: 写失败测试 `ChatInvokerTest`**

```java
package com.hify.conversation.service;

import com.hify.conversation.constant.MessageRole;
import com.hify.conversation.entity.Message;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ChatInvokerTest {

    private final ChatInvoker invoker = new ChatInvoker();

    private Message msg(String role, String content) {
        Message m = new Message();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    void toMessages_有systemPrompt_首位System_其后按窗口顺序映射角色() {
        List<org.springframework.ai.chat.messages.Message> out = invoker.toMessages("你是客服", List.of(
                msg(MessageRole.USER.value(), "a"),
                msg(MessageRole.ASSISTANT.value(), "b"),
                msg(MessageRole.USER.value(), "c")));

        assertEquals(4, out.size());
        assertInstanceOf(SystemMessage.class, out.get(0));
        assertInstanceOf(UserMessage.class, out.get(1));
        assertInstanceOf(AssistantMessage.class, out.get(2));
        assertInstanceOf(UserMessage.class, out.get(3));
        assertEquals("c", out.get(3).getText()); // 末位即当前消息
    }

    @Test
    void toMessages_systemPrompt为空_不加System段() {
        List<org.springframework.ai.chat.messages.Message> out = invoker.toMessages(null, List.of(
                msg(MessageRole.USER.value(), "仅当前")));

        assertEquals(1, out.size());
        assertInstanceOf(UserMessage.class, out.get(0));
        assertEquals("仅当前", out.get(0).getText());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd server && mvn test -Dtest=ChatInvokerTest`
Expected: 编译失败 / `cannot find symbol: method toMessages`。

- [ ] **Step 3: 在 ChatInvoker 加 toMessages（仅新增，不动 invoke）**

在 `ChatInvoker` 类体内、`invoke` 方法之前插入下列方法，并补 import：

```java
// —— 新增 import ——
import com.hify.conversation.constant.MessageRole;
import com.hify.conversation.entity.Message;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import java.util.ArrayList;
import java.util.List;
```

```java
    /** 领域消息窗口 → Spring AI 消息列表：systemPrompt 有文本则首位 System，其后按窗口顺序映射角色，末位即当前消息。 */
    List<org.springframework.ai.chat.messages.Message> toMessages(String systemPrompt, List<Message> window) {
        List<org.springframework.ai.chat.messages.Message> out = new ArrayList<>();
        if (StringUtils.hasText(systemPrompt)) {
            out.add(new SystemMessage(systemPrompt));
        }
        for (Message m : window) {
            out.add(MessageRole.ASSISTANT.value().equals(m.getRole())
                    ? new AssistantMessage(m.getContent())
                    : new UserMessage(m.getContent()));
        }
        return out;
    }
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd server && mvn test -Dtest=ChatInvokerTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/java/com/hify/conversation/service/ChatInvoker.java \
        server/src/test/java/com/hify/conversation/service/ChatInvokerTest.java
git commit -m "feat(conversation): ChatInvoker.toMessages 领域消息→Spring AI 消息映射（多轮地基）"
```

---

### Task 2: 多轮核心 —— openTurn 返窗口 + invoke 喂窗口 + 编排接通

配置 + `TurnContext` + `openTurn` 在事务内读窗口并返回 `(cid, window)` + `ChatInvoker.invoke` 改吃窗口 + `ConversationService.send` 把窗口喂模型。`openTurn`/`invoke` 签名变更牵连两个测试文件，本任务一并改，结束时编译绿、历史进 prompt。

**Files:**
- Create: `server/src/main/java/com/hify/conversation/config/ConversationProperties.java`
- Create: `server/src/main/java/com/hify/conversation/config/ConversationConfig.java`
- Create: `server/src/main/java/com/hify/conversation/service/TurnContext.java`
- Modify: `server/src/main/resources/application.yml`
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationStore.java`
- Modify: `server/src/main/java/com/hify/conversation/service/ChatInvoker.java`
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java`
- Test: `server/src/test/java/com/hify/conversation/service/ConversationStoreTest.java`（改）
- Test: `server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java`（改）

**Interfaces:**
- Consumes: `ChatInvoker.toMessages(...)`（Task 1）
- Produces:
  - `record TurnContext(Long conversationId, List<Message> window)`
  - `TurnContext ConversationStore.openTurn(Long appId, Long conversationId, Long userId, String userContent)` —— 返回类型由 `Long` 改为 `TurnContext`
  - `LlmReply ChatInvoker.invoke(ChatClient chatClient, String systemPrompt, List<Message> window)` —— 第三参由 `String` 改为 `List<Message>`
  - `ConversationProperties(Memory memory)`，`Memory(int windowRounds)`

- [ ] **Step 1: 新建 ConversationProperties + ConversationConfig**

`server/src/main/java/com/hify/conversation/config/ConversationProperties.java`：
```java
package com.hify.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * conversation 模块配置（hify.conversation.*）。
 * memory.window-rounds：滑动窗口轮数 N，最近 N 轮(2N 条) + 当前消息进 prompt。
 */
@ConfigurationProperties(prefix = "hify.conversation")
public record ConversationProperties(Memory memory) {

    public record Memory(int windowRounds) {
    }
}
```

`server/src/main/java/com/hify/conversation/config/ConversationConfig.java`：
```java
package com.hify.conversation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ConversationProperties.class)
public class ConversationConfig {
}
```

- [ ] **Step 2: application.yml 增配置**

在 `application.yml` 的 `hify:` 段下（与 `identity`/`provider` 同级）加：
```yaml
  conversation:
    memory:
      # 滑动窗口轮数 N：最近 N 轮(2N 条) + 当前消息进 prompt。改记忆长度只动这里。
      window-rounds: ${HIFY_CONVERSATION_WINDOW_ROUNDS:10}
```

- [ ] **Step 3: 新建 TurnContext**

`server/src/main/java/com/hify/conversation/service/TurnContext.java`：
```java
package com.hify.conversation.service;

import com.hify.conversation.entity.Message;

import java.util.List;

/** openTurn 的结果（模块内）：本次会话 id + 喂给模型的消息窗口（时间正序，末位为当前消息）。 */
public record TurnContext(Long conversationId, List<Message> window) {
}
```

- [ ] **Step 4: 改 ConversationStoreTest（红）**

整体替换为下文（构造器加 props、openTurn 返回 TurnContext、新增窗口测试、setUp 默认 stub selectList）：
```java
package com.hify.conversation.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.conversation.config.ConversationProperties;
import com.hify.conversation.constant.MessageRole;
import com.hify.conversation.entity.Conversation;
import com.hify.conversation.entity.Message;
import com.hify.conversation.mapper.ConversationMapper;
import com.hify.conversation.mapper.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.List;

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
        store = new ConversationStore(conversationMapper, messageMapper,
                new ConversationProperties(new ConversationProperties.Memory(10)));
        // 默认窗口读返回空（具体测试再覆盖）；strict-stub 未启用，多余 stub 无碍。
        when(messageMapper.selectList(any())).thenReturn(new ArrayList<>());
    }

    private void stubInsertAssignsId(Long id) {
        when(conversationMapper.insert(any(Conversation.class))).thenAnswer((InvocationOnMock inv) -> {
            inv.getArgument(0, Conversation.class).setId(id);
            return 1;
        });
    }

    private Message msg(Long id, String role, String content) {
        Message m = new Message();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    void openTurn_新会话_建会话取title并落user消息_返回新cid() {
        stubInsertAssignsId(100L);
        ArgumentCaptor<Conversation> cc = ArgumentCaptor.forClass(Conversation.class);
        ArgumentCaptor<Message> mc = ArgumentCaptor.forClass(Message.class);

        TurnContext tc = store.openTurn(7L, null, 42L, "  你好，介绍一下你自己  ");

        assertEquals(100L, tc.conversationId());
        verify(conversationMapper).insert((Conversation) cc.capture());
        assertEquals(7L, cc.getValue().getAppId());
        assertEquals(42L, cc.getValue().getUserId());
        assertEquals("你好，介绍一下你自己", cc.getValue().getTitle()); // 已 strip
        verify(messageMapper).insert((Message) mc.capture());
        assertEquals(100L, mc.getValue().getConversationId());
        assertEquals(MessageRole.USER.value(), mc.getValue().getRole());
        assertEquals("你好，介绍一下你自己", mc.getValue().getContent());
    }

    @Test
    void openTurn_返回窗口_按id正序() {
        stubInsertAssignsId(100L);
        Message m1 = msg(1L, MessageRole.USER.value(), "a");
        Message m2 = msg(2L, MessageRole.ASSISTANT.value(), "b");
        // mapper 按 id desc 返回，store 反转为时间正序
        when(messageMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(m2, m1)));

        TurnContext tc = store.openTurn(7L, null, 42L, "你好");

        assertEquals(List.of(m1, m2), tc.window());
    }

    @Test
    void openTurn_续聊本人会话_不建会话_只落user消息() {
        Conversation existing = new Conversation();
        existing.setId(100L);
        existing.setUserId(42L);
        when(conversationMapper.selectById(eq(100L))).thenReturn(existing);

        TurnContext tc = store.openTurn(7L, 100L, 42L, "继续");

        assertEquals(100L, tc.conversationId());
        verify(conversationMapper, never()).insert(any(Conversation.class));
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
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    void appendAssistant_落assistant消息含token_并touch会话() {
        ArgumentCaptor<Message> mc = ArgumentCaptor.forClass(Message.class);
        Message saved = store.appendAssistant(100L, "你好，我是助手", 12, 8);

        verify(messageMapper).insert((Message) mc.capture());
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

- [ ] **Step 5: 改 ConversationServiceTest（红）—— 改 stub 为 TurnContext + invoke 喂窗口**

整体替换为下文（openTurn 返回 TurnContext；invoke 第三参为窗口；单轮测试改为多轮喂窗口；新增 userMsg/assistantMsg 辅助）：
```java
package com.hify.conversation.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.AppRuntimeView;
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
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
                .thenReturn(Optional.of(new AppRuntimeView(7L, 5L, systemPrompt)));
        when(providerFacade.getChatClient(eq(5L))).thenReturn(chatClient);
    }

    private Message userMsg(String content) {
        Message m = new Message();
        m.setRole(MessageRole.USER.value());
        m.setContent(content);
        return m;
    }

    private Message assistantMsg(String content) {
        Message m = new Message();
        m.setRole(MessageRole.ASSISTANT.value());
        m.setContent(content);
        return m;
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
        List<Message> window = List.of(userMsg("你好"));
        when(store.openTurn(eq(7L), eq(null), eq(42L), eq("你好")))
                .thenReturn(new TurnContext(100L, window));
        when(chatInvoker.invoke(eq(chatClient), eq("你是客服"), eq(window)))
                .thenReturn(new LlmReply("你好，我是助手", 12, 8));
        when(store.appendAssistant(eq(100L), eq("你好，我是助手"), eq(12), eq(8)))
                .thenReturn(savedAssistant());

        SendMessageResponse resp = service.send(7L, null, "你好", member);

        // 配额检查在落库前；窗口喂模型在两次 store 写之间
        InOrder order = inOrder(quotaGuard, store, chatInvoker);
        order.verify(quotaGuard).check(42L, 7L);
        order.verify(store).openTurn(7L, null, 42L, "你好");
        order.verify(chatInvoker).invoke(chatClient, "你是客服", window);
        order.verify(store).appendAssistant(100L, "你好，我是助手", 12, 8);

        assertEquals(100L, resp.conversationId());
        assertEquals(200L, resp.message().id());
        assertEquals("assistant", resp.message().role());
        assertEquals("你好，我是助手", resp.message().content());
        assertEquals(12, resp.message().promptTokens());
    }

    @Test
    void send_多轮_把store返回的窗口整体喂模型() {
        stubRunnableApp(null);
        List<Message> window = List.of(userMsg("第一句"), assistantMsg("回复一"), userMsg("第二句"));
        when(store.openTurn(any(), any(), any(), any())).thenReturn(new TurnContext(100L, window));
        when(chatInvoker.invoke(any(), any(), any())).thenReturn(new LlmReply("ok", 1, 1));
        when(store.appendAssistant(any(), any(), anyInt(), anyInt())).thenReturn(savedAssistant());

        service.send(7L, 100L, "第二句", member);

        // 窗口原样透传给 invoker（含历史，末位当前消息），systemPrompt 为 null 透传
        verify(chatInvoker).invoke(eq(chatClient), eq(null), eq(window));
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
                .thenReturn(Optional.of(new AppRuntimeView(7L, 5L, null)));
        when(store.openTurn(any(), any(), any(), any()))
                .thenReturn(new TurnContext(100L, List.of(userMsg("你好"))));
        when(providerFacade.getChatClient(eq(5L)))
                .thenThrow(new BizException(ProviderError.MODEL_NOT_USABLE));

        BizException ex = assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));
        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
        verify(store).openTurn(7L, null, 42L, "你好"); // 事务A 已发生
        verify(store, never()).appendAssistant(any(), any(), anyInt(), anyInt());
    }

    @Test
    void send_模型调用故障_透传12003_不落assistant() {
        stubRunnableApp(null);
        when(store.openTurn(any(), any(), any(), any()))
                .thenReturn(new TurnContext(100L, List.of(userMsg("你好"))));
        when(chatInvoker.invoke(any(), any(), any()))
                .thenThrow(new BizException(ProviderError.PROVIDER_UNAVAILABLE));

        BizException ex = assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));
        assertEquals(ProviderError.PROVIDER_UNAVAILABLE, ex.errorCode());
        verify(store, never()).appendAssistant(any(), any(), anyInt(), anyInt());
    }

    @Test
    void history_委托store_按当前用户过滤() {
        Message m = savedAssistant();
        when(store.listMessages(eq(100L), eq(42L))).thenReturn(List.of(m));

        var list = service.history(100L, member);

        assertEquals(1, list.size());
        assertEquals(200L, list.get(0).id());
        verify(store).listMessages(100L, 42L);
    }
}
```

- [ ] **Step 6: 运行两个测试，确认失败（编译错误/红）**

Run: `cd server && mvn test -Dtest=ConversationStoreTest,ConversationServiceTest`
Expected: 编译失败（openTurn 返回类型不符 / invoke 参数不符）。

- [ ] **Step 7: 改 ConversationStore —— 注入 props、openTurn 返 TurnContext、读窗口**

补 import：
```java
import com.hify.conversation.config.ConversationProperties;
import java.util.Collections;
```
改构造器与 openTurn，并新增私有 readWindow：
```java
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ConversationProperties props;

    public ConversationStore(ConversationMapper conversationMapper, MessageMapper messageMapper,
                             ConversationProperties props) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.props = props;
    }

    /** 事务A：解析/新建会话 + 落 user 消息 + 同事务读最近窗口，返回 (cid, window)。 */
    @Transactional
    public TurnContext openTurn(Long appId, Long conversationId, Long userId, String userContent) {
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
        return new TurnContext(cid, readWindow(cid));
    }

    /** 读最近 2N+1 条（N 轮历史 + 当前消息），时间正序。@TableLogic 自动加 deleted=false。 */
    private List<Message> readWindow(Long conversationId) {
        int limit = 2 * props.memory().windowRounds() + 1;
        List<Message> desc = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getId)
                .last("limit " + limit));
        Collections.reverse(desc);
        return desc;
    }
```
（保留 `appendAssistant`、`listMessages`、`assertOwned`、`titleFrom` 不变。）

- [ ] **Step 8: 改 ChatInvoker.invoke —— 吃窗口、用 toMessages**

把 `invoke` 整体替换为下文（删去旧的 `ChatClient.ChatClientRequestSpec spec` 写法）：
```java
    public LlmReply invoke(ChatClient chatClient, String systemPrompt, List<Message> window) {
        ChatResponse resp = chatClient.prompt()
                .messages(toMessages(systemPrompt, window))
                .call().chatResponse();
        String content = resp.getResult().getOutput().getText();
        Usage usage = resp.getMetadata().getUsage();
        int promptTokens = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int completionTokens = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        return new LlmReply(content, promptTokens, completionTokens);
    }
```

- [ ] **Step 9: 改 ConversationService.send —— 解包 cid、喂窗口**

把 send 的第 3-4 步替换为：
```java
        // 3) 事务A：建/取会话 + 落 user 消息 + 读窗口
        TurnContext turn = store.openTurn(appId, conversationId, current.userId(), content);
        Long cid = turn.conversationId();
        // 4) 取 ChatClient（不可用抛 12002）并调用——事务外，喂历史窗口
        ChatClient chatClient = providerFacade.getChatClient(app.modelId());
        LlmReply reply = chatInvoker.invoke(chatClient, app.systemPrompt(), turn.window());
```
（第 5 步 `store.appendAssistant(cid, ...)` 不变。）

- [ ] **Step 10: 运行测试，确认通过**

Run: `cd server && mvn test -Dtest=ChatInvokerTest,ConversationStoreTest,ConversationServiceTest`
Expected: 三类全绿（`Failures: 0, Errors: 0`）。

- [ ] **Step 11: 提交**

```bash
git add server/src/main/java/com/hify/conversation/ server/src/main/resources/application.yml \
        server/src/test/java/com/hify/conversation/service/ConversationStoreTest.java \
        server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java
git commit -m "feat(conversation): 多轮记忆核心——openTurn 返窗口、invoke 喂窗口、滑动窗口N=10"
```

---

### Task 3: 会话列表后端端点

新增 `GET /api/v1/conversation/conversations?appId=`：本人在该 app 下最近活跃会话（不分页，cap 50）。

**Files:**
- Modify: `server/src/main/java/com/hify/conversation/config/ConversationProperties.java`（加 ListProps）
- Modify: `server/src/main/resources/application.yml`（加 list.recent-limit）
- Create: `server/src/main/java/com/hify/conversation/dto/ConversationView.java`
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationStore.java`（listConversations）
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java`（listConversations）
- Modify: `server/src/main/java/com/hify/conversation/controller/ConversationController.java`（路由调整 + 新端点）
- Test: `ConversationStoreTest`、`ConversationServiceTest`、`ConversationControllerTest`（均改）

**Interfaces:**
- Produces:
  - `record ConversationView(Long id, String title, OffsetDateTime updateTime)`
  - `List<Conversation> ConversationStore.listConversations(Long appId, Long userId)`
  - `List<ConversationView> ConversationService.listConversations(Long appId, CurrentUser current)`
  - `GET /api/v1/conversation/conversations?appId=` → `Result<List<ConversationView>>`

- [ ] **Step 1: ConversationProperties 加 ListProps + yml**

`ConversationProperties` 改为：
```java
@ConfigurationProperties(prefix = "hify.conversation")
public record ConversationProperties(Memory memory, ListProps list) {

    public record Memory(int windowRounds) {
    }

    public record ListProps(int recentLimit) {
    }
}
```
`application.yml` 的 `hify.conversation` 段补：
```yaml
    list:
      # 会话侧边栏返回的最近会话条数上限（按 update_time desc，不分页）。
      recent-limit: ${HIFY_CONVERSATION_LIST_LIMIT:50}
```

- [ ] **Step 2: 修 ConversationStoreTest setUp 构造器（编译跟进）**

把 setUp 里构造 store 一行改为：
```java
        store = new ConversationStore(conversationMapper, messageMapper,
                new ConversationProperties(new ConversationProperties.Memory(10),
                        new ConversationProperties.ListProps(50)));
```

- [ ] **Step 3: 写会话列表的失败测试（红）**

在 `ConversationStoreTest` 末尾（最后一个 `}` 之前）加：
```java
    @Test
    void listConversations_委托mapper_返回结果() {
        Conversation c = new Conversation();
        c.setId(1L);
        c.setUserId(42L);
        c.setAppId(7L);
        c.setTitle("会话一");
        when(conversationMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(c)));

        List<Conversation> list = store.listConversations(7L, 42L);

        assertEquals(1, list.size());
        assertEquals(1L, list.get(0).getId());
    }
```
在 `ConversationServiceTest` 末尾加（先补 import `com.hify.conversation.dto.ConversationView` 与 `java.time.OffsetDateTime`）：
```java
    @Test
    void listConversations_委托store_映射为视图() {
        Conversation c = new Conversation();
        c.setId(1L);
        c.setTitle("会话一");
        c.setUpdateTime(OffsetDateTime.parse("2026-06-29T10:00:00+08:00"));
        when(store.listConversations(eq(7L), eq(42L))).thenReturn(List.of(c));

        List<ConversationView> views = service.listConversations(7L, member);

        assertEquals(1, views.size());
        assertEquals(1L, views.get(0).id());
        assertEquals("会话一", views.get(0).title());
        verify(store).listConversations(7L, 42L);
    }
```
（`ConversationServiceTest` 顶部补 import：`com.hify.conversation.entity.Conversation`、`com.hify.conversation.dto.ConversationView`、`java.time.OffsetDateTime`。）

在 `ConversationControllerTest` 末尾加（顶部已 import `OffsetDateTime`、`List`）：
```java
    private com.hify.conversation.dto.ConversationView conv() {
        return new com.hify.conversation.dto.ConversationView(100L, "你好",
                OffsetDateTime.parse("2026-06-29T10:00:00+08:00"));
    }

    @Test
    void 会话列表_返回数组_id为string() throws Exception {
        when(conversationService.listConversations(eq(7L), any())).thenReturn(List.of(conv()));
        mockMvc.perform(get("/api/v1/conversation/conversations?appId=7")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("100"))   // Long→string
                .andExpect(jsonPath("$.data[0].title").value("你好"));
    }
```

- [ ] **Step 4: 运行测试，确认失败**

Run: `cd server && mvn test -Dtest=ConversationStoreTest,ConversationServiceTest,ConversationControllerTest`
Expected: 编译失败（`ConversationView` / `listConversations` 未定义）。

- [ ] **Step 5: 新建 ConversationView**

`server/src/main/java/com/hify/conversation/dto/ConversationView.java`：
```java
package com.hify.conversation.dto;

import java.time.OffsetDateTime;

/** 会话列表项（成员族响应）：id 为 Long（infra 序列化为 string）；title 取首条消息截断；updateTime 为最近活跃时间。 */
public record ConversationView(Long id, String title, OffsetDateTime updateTime) {
}
```

- [ ] **Step 6: ConversationStore.listConversations**

在 `listMessages` 之后加（`LambdaQueryWrapper` 已 import）：
```java
    /** 读：本人在某 app 下最近活跃会话（update_time desc，cap recent-limit）。@TableLogic 自动加 deleted=false。 */
    public List<Conversation> listConversations(Long appId, Long userId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getAppId, appId)
                .orderByDesc(Conversation::getUpdateTime)
                .last("limit " + props.list().recentLimit()));
    }
```

- [ ] **Step 7: ConversationService.listConversations**

补 import `com.hify.conversation.dto.ConversationView;`，在 `history` 方法后加：
```java
    public List<ConversationView> listConversations(Long appId, CurrentUser current) {
        return store.listConversations(appId, current.userId()).stream()
                .map(c -> new ConversationView(c.getId(), c.getTitle(), c.getUpdateTime()))
                .toList();
    }
```

- [ ] **Step 8: ConversationController 路由调整 + 新端点**

类级注解改为 `@RequestMapping("/api/v1/conversation")`；既有两方法补方法级路径 `"/messages"`；新增列表端点。补 import `com.hify.conversation.dto.ConversationView;`：
```java
@RestController
@RequestMapping("/api/v1/conversation")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping("/messages")
    public Result<SendMessageResponse> send(@Valid @RequestBody SendMessageRequest request) {
        return Result.ok(conversationService.send(
                request.appId(), request.conversationId(), request.content(), CurrentUserHolder.current()));
    }

    @GetMapping("/messages")
    public Result<List<MessageView>> history(@RequestParam Long conversationId) {
        return Result.ok(conversationService.history(conversationId, CurrentUserHolder.current()));
    }

    @GetMapping("/conversations")
    public Result<List<ConversationView>> listConversations(@RequestParam Long appId) {
        return Result.ok(conversationService.listConversations(appId, CurrentUserHolder.current()));
    }
}
```

- [ ] **Step 9: 运行测试，确认通过**

Run: `cd server && mvn test -Dtest=ConversationStoreTest,ConversationServiceTest,ConversationControllerTest`
Expected: 全绿（既有 messages 路由 URL 不变，原有用例继续通过）。

- [ ] **Step 10: 后端全量回归（含 ModularityTests / LayerRulesTest）**

Run: `cd server && mvn test`
Expected: `Failures: 0, Errors: 0`（模块边界、DTO 不 import entity 等约束保持）。

- [ ] **Step 11: 提交**

```bash
git add server/src/main/java/com/hify/conversation/ server/src/main/resources/application.yml \
        server/src/test/java/com/hify/conversation/
git commit -m "feat(conversation): 会话列表端点 GET /conversations（不分页 cap50，update_time desc）"
```

---

### Task 4: 前端 api + 类型（listConversations / ConversationView）

**Files:**
- Modify: `web/src/types/conversation.ts`
- Modify: `web/src/api/conversation.ts`
- Test: `web/src/api/__tests__/conversation.spec.ts`（改）

**Interfaces:**
- Produces: `interface ConversationView { id: string; title: string | null; updateTime: string }`；`listConversations(appId: string): Promise<ConversationView[]>`

- [ ] **Step 1: 写失败测试**

在 `web/src/api/__tests__/conversation.spec.ts` 顶部 import 改为引入 `listConversations`：
```ts
import { sendMessage, getMessages, listConversations } from '@/api/conversation'
```
在 `describe` 内末尾加：
```ts
  it('listConversations → GET /conversation/conversations?appId', () => {
    listConversations('7')
    expect(request.get).toHaveBeenCalledWith('/conversation/conversations', {
      params: { appId: '7' },
    })
  })
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd web && pnpm vitest run src/api/__tests__/conversation.spec.ts`
Expected: 失败（`listConversations` is not a function / 未导出）。

- [ ] **Step 3: types 加 ConversationView**

在 `web/src/types/conversation.ts` 末尾加：
```ts
/** 会话列表项（对齐后端 ConversationView）。id 为 string（Long）；title 可空；updateTime 为最近活跃时间。 */
export interface ConversationView {
  id: string
  title: string | null
  updateTime: string
}
```

- [ ] **Step 4: api 加 listConversations**

`web/src/api/conversation.ts`：import 补 `ConversationView`，文件末尾加函数：
```ts
import type { MessageView, SendMessageResponse, ConversationView } from '@/types/conversation'
```
```ts
/** 拉本人在某 app 下最近会话（不分页，最近 N 条）。后端：GET /api/v1/conversation/conversations?appId= */
export function listConversations(appId: string) {
  return request.get<ConversationView[]>('/conversation/conversations', { params: { appId } })
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `cd web && pnpm vitest run src/api/__tests__/conversation.spec.ts`
Expected: 全绿。

- [ ] **Step 6: 提交**

```bash
git add web/src/types/conversation.ts web/src/api/conversation.ts \
        web/src/api/__tests__/conversation.spec.ts
git commit -m "feat(web): conversation api 加 listConversations + ConversationView 类型"
```

---

### Task 5: useConversationStore（Pinia）

**Files:**
- Create: `web/src/stores/conversation.ts`
- Test: `web/src/stores/__tests__/conversation.spec.ts`（新建）

**Interfaces:**
- Produces: `useConversationStore()` →
  - state: `conversations`, `messages`, `currentId`, `loadingList`, `sending`
  - actions: `loadConversations(appId)`, `loadMessages(cid)`, `newConversation()`, `send(appId, content): Promise<string>`

- [ ] **Step 1: 写失败测试 `stores/__tests__/conversation.spec.ts`**

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { sendMessage, getMessages, listConversations } from '@/api/conversation'
import { useConversationStore } from '@/stores/conversation'

vi.mock('@/api/conversation', () => ({
  sendMessage: vi.fn(),
  getMessages: vi.fn(),
  listConversations: vi.fn(),
}))

const assistant = {
  id: '200', role: 'assistant' as const, content: '你好，我是助手',
  promptTokens: 12, completionTokens: 8, createTime: '2026-06-29T10:00:00+08:00',
}

describe('useConversationStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loadConversations 填充列表', async () => {
    vi.mocked(listConversations).mockResolvedValue([
      { id: '1', title: '会话一', updateTime: '2026-06-29T10:00:00+08:00' },
    ])
    const store = useConversationStore()
    await store.loadConversations('7')
    expect(listConversations).toHaveBeenCalledWith('7')
    expect(store.conversations).toHaveLength(1)
  })

  it('loadMessages 设当前 id 并载入历史', async () => {
    vi.mocked(getMessages).mockResolvedValue([assistant])
    const store = useConversationStore()
    await store.loadMessages('100')
    expect(getMessages).toHaveBeenCalledWith('100')
    expect(store.currentId).toBe('100')
    expect(store.messages).toEqual([assistant])
  })

  it('newConversation 清空当前会话与消息', () => {
    const store = useConversationStore()
    store.currentId = '100'
    store.messages = [assistant]
    store.newConversation()
    expect(store.currentId).toBeNull()
    expect(store.messages).toEqual([])
  })

  it('send 新会话：先渲染用户气泡再追加助手回复，回写 currentId 并返回 cid', async () => {
    vi.mocked(sendMessage).mockResolvedValue({ conversationId: '100', message: assistant })
    const store = useConversationStore()
    const cid = await store.send('7', '你好')
    expect(sendMessage).toHaveBeenCalledWith('7', null, '你好') // 新会话 currentId 为 null
    expect(cid).toBe('100')
    expect(store.currentId).toBe('100')
    expect(store.messages).toHaveLength(2) // user + assistant
    expect(store.messages[0].role).toBe('user')
    expect(store.messages[1]).toEqual(assistant)
  })

  it('send 续聊：复用 currentId', async () => {
    vi.mocked(sendMessage).mockResolvedValue({ conversationId: '100', message: assistant })
    const store = useConversationStore()
    store.currentId = '100'
    await store.send('7', '第二句')
    expect(sendMessage).toHaveBeenCalledWith('7', '100', '第二句')
  })
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd web && pnpm vitest run src/stores/__tests__/conversation.spec.ts`
Expected: 失败（`@/stores/conversation` 不存在）。

- [ ] **Step 3: 实现 store `web/src/stores/conversation.ts`**

```ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { sendMessage, getMessages, listConversations } from '@/api/conversation'
import type { MessageView, ConversationView } from '@/types/conversation'

/**
 * 会话运行态：侧边栏列表 + 当前会话消息 + 当前会话 id。个人数据，仅本人可见。
 * 当前会话 id 的「真相」在 URL query（见 ChatView），本 store 只持运行态，不做持久化。
 */
export const useConversationStore = defineStore('conversation', () => {
  const conversations = ref<ConversationView[]>([])
  const messages = ref<MessageView[]>([])
  const currentId = ref<string | null>(null)
  const loadingList = ref(false)
  const sending = ref(false)

  /** 拉侧边栏会话列表（最近 N 条，不分页）。 */
  async function loadConversations(appId: string) {
    loadingList.value = true
    try {
      conversations.value = await listConversations(appId)
    } finally {
      loadingList.value = false
    }
  }

  /** 载入某会话历史到聊天区（切换/刷新恢复时调用）。 */
  async function loadMessages(conversationId: string) {
    currentId.value = conversationId
    messages.value = await getMessages(conversationId)
  }

  /** 进入「新会话」空白态（不发请求）。 */
  function newConversation() {
    currentId.value = null
    messages.value = []
  }

  /**
   * 发送一条消息：先本地渲染用户气泡，再追加助手回复。
   * 返回本次会话 id（新会话由后端生成），交调用方写回 URL 并刷新列表。
   */
  async function send(appId: string, content: string): Promise<string> {
    messages.value.push({
      id: `local-${Date.now()}`,
      role: 'user',
      content,
      promptTokens: null,
      completionTokens: null,
      createTime: new Date().toISOString(),
    })
    sending.value = true
    try {
      const res = await sendMessage(appId, currentId.value, content)
      currentId.value = res.conversationId
      messages.value.push(res.message)
      return res.conversationId
    } finally {
      sending.value = false
    }
  }

  return {
    conversations,
    messages,
    currentId,
    loadingList,
    sending,
    loadConversations,
    loadMessages,
    newConversation,
    send,
  }
})
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd web && pnpm vitest run src/stores/__tests__/conversation.spec.ts`
Expected: 全绿（5 passed）。

- [ ] **Step 5: 提交**

```bash
git add web/src/stores/conversation.ts web/src/stores/__tests__/conversation.spec.ts
git commit -m "feat(web): useConversationStore（会话列表/历史/发送运行态）"
```

---

### Task 6: ConversationSidebar.vue（侧边栏列表组件）

本页局部组件（不进全局公共组件，守 rule-of-three）。props 进、事件出，无自身数据请求。

**Files:**
- Create: `web/src/views/conversation/ConversationSidebar.vue`
- Test: `web/src/views/conversation/__tests__/ConversationSidebar.spec.ts`（新建）

**Interfaces:**
- Consumes: `ConversationView`（Task 4）
- Produces: props `{ conversations: ConversationView[], currentId: string | null }`；emits `select(id: string)`、`new()`

- [ ] **Step 1: 写失败测试 `__tests__/ConversationSidebar.spec.ts`**

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConversationSidebar from '@/views/conversation/ConversationSidebar.vue'

const convs = [
  { id: '1', title: '会话一', updateTime: '2026-06-29T10:00:00+08:00' },
  { id: '2', title: '会话二', updateTime: '2026-06-29T09:00:00+08:00' },
]

function mountSidebar(currentId: string | null = null) {
  return mount(ConversationSidebar, {
    props: { conversations: convs, currentId },
    global: { plugins: [ElementPlus] },
  })
}

describe('ConversationSidebar', () => {
  it('渲染会话列表', () => {
    const wrapper = mountSidebar()
    expect(wrapper.findAll('[data-test="conv-item"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('会话一')
  })

  it('点击会话 emit select 带 id', async () => {
    const wrapper = mountSidebar()
    await wrapper.findAll('[data-test="conv-item"]')[1].trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['2'])
  })

  it('点击新建 emit new', async () => {
    const wrapper = mountSidebar()
    await wrapper.find('[data-test="conv-new"]').trigger('click')
    expect(wrapper.emitted('new')).toHaveLength(1)
  })

  it('当前会话高亮', () => {
    const wrapper = mountSidebar('2')
    const active = wrapper.find('.sidebar__item--active')
    expect(active.text()).toBe('会话二')
  })
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ConversationSidebar.spec.ts`
Expected: 失败（组件不存在）。

- [ ] **Step 3: 实现组件 `web/src/views/conversation/ConversationSidebar.vue`**

```vue
<script setup lang="ts">
import type { ConversationView } from '@/types/conversation'

defineProps<{
  conversations: ConversationView[]
  currentId: string | null
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
  (e: 'new'): void
}>()
</script>

<template>
  <aside class="sidebar">
    <el-button class="sidebar__new" type="primary" data-test="conv-new" @click="emit('new')">
      新建会话
    </el-button>
    <ul class="sidebar__list">
      <li
        v-for="c in conversations"
        :key="c.id"
        :class="['sidebar__item', { 'sidebar__item--active': c.id === currentId }]"
        data-test="conv-item"
        :title="c.title ?? '未命名会话'"
        @click="emit('select', c.id)"
      >
        {{ c.title ?? '未命名会话' }}
      </li>
    </ul>
  </aside>
</template>

<style scoped lang="scss">
.sidebar {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 220px;
  padding: 12px;
  border-right: 1px solid var(--el-border-color-light);

  &__new {
    width: 100%;
  }

  &__list {
    flex: 1;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    list-style: none;
  }

  &__item {
    padding: 8px 10px;
    border-radius: 6px;
    cursor: pointer;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;

    &:hover {
      background: var(--el-fill-color-light);
    }

    &--active {
      background: var(--el-color-primary-light-8);
    }
  }
}
</style>
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ConversationSidebar.spec.ts`
Expected: 全绿（4 passed）。

- [ ] **Step 5: 提交**

```bash
git add web/src/views/conversation/ConversationSidebar.vue \
        web/src/views/conversation/__tests__/ConversationSidebar.spec.ts
git commit -m "feat(web): ConversationSidebar 会话列表组件（列表/新建/高亮，标题省略号）"
```

---

### Task 7: ChatView 升级为两栏 + URL query + store

ChatView 改用 store；URL `?c=<cid>` 为当前会话 id 真相；挂载拉列表、watch query 载历史；新会话首发后 `router.replace` 写回 cid 并刷新列表。**无需改 router 文件**（query 不属路径）。

**Files:**
- Modify: `web/src/views/conversation/ChatView.vue`
- Test: `web/src/views/conversation/__tests__/ChatView.spec.ts`（改）

**Interfaces:**
- Consumes: `useConversationStore`（Task 5）、`ConversationSidebar`（Task 6）

- [ ] **Step 1: 改写 ChatView 测试（红）**

整体替换 `web/src/views/conversation/__tests__/ChatView.spec.ts`：
```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import { sendMessage, getMessages, listConversations } from '@/api/conversation'
import ChatView from '@/views/conversation/ChatView.vue'

vi.mock('@/api/conversation', () => ({
  sendMessage: vi.fn(),
  getMessages: vi.fn(),
  listConversations: vi.fn(),
}))

const routeQuery: { c?: string } = {}
const push = vi.fn()
const replace = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { appId: '7' }, query: routeQuery }),
  useRouter: () => ({ push, replace }),
}))

globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const assistant = {
  id: '200', role: 'assistant' as const, content: '你好，我是助手',
  promptTokens: 12, completionTokens: 8, createTime: '2026-06-29T10:00:00+08:00',
}

describe('ChatView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    delete routeQuery.c
    vi.mocked(listConversations).mockResolvedValue([])
    vi.mocked(getMessages).mockResolvedValue([])
    vi.mocked(sendMessage).mockResolvedValue({ conversationId: '100', message: assistant })
  })

  it('挂载即拉会话列表', async () => {
    mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listConversations).toHaveBeenCalledWith('7')
  })

  it('发送后渲染用户气泡与助手回复，新会话 conversationId 为 null', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="chat-input"] textarea').setValue('你好')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()
    expect(sendMessage).toHaveBeenCalledWith('7', null, '你好')
    expect(wrapper.findAll('[data-test="msg"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('你好，我是助手')
  })

  it('新会话首发后把 conversationId 写回 URL（replace）', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="chat-input"] textarea').setValue('你好')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()
    expect(replace).toHaveBeenCalledWith({ query: { c: '100' } })
  })

  it('URL 带 c：挂载即载入该会话历史（刷新恢复）', async () => {
    routeQuery.c = '100'
    vi.mocked(getMessages).mockResolvedValue([assistant])
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(getMessages).toHaveBeenCalledWith('100')
    expect(wrapper.text()).toContain('你好，我是助手')
  })

  it('点击侧边栏会话切换（push query.c）', async () => {
    vi.mocked(listConversations).mockResolvedValue([
      { id: '5', title: '旧会话', updateTime: '2026-06-29T09:00:00+08:00' },
    ])
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="conv-item"]').trigger('click')
    expect(push).toHaveBeenCalledWith({ query: { c: '5' } })
  })

  it('空白输入不触发发送', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="chat-input"] textarea').setValue('   ')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()
    expect(sendMessage).not.toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ChatView.spec.ts`
Expected: 失败（旧 ChatView 无侧边栏 / 未用 store / 未调 listConversations）。

- [ ] **Step 3: 改写 ChatView `web/src/views/conversation/ChatView.vue`**

```vue
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useConversationStore } from '@/stores/conversation'
import ConversationSidebar from './ConversationSidebar.vue'

const route = useRoute()
const router = useRouter()
const appId = route.params.appId as string

const store = useConversationStore()
const { conversations, messages, currentId, sending } = storeToRefs(store)

const input = ref('')

// URL query.c 是当前会话 id 的「真相」：刷新/切换都以它为准载入历史。
const queryCid = computed(() => (route.query.c as string | undefined) ?? null)

watch(
  queryCid,
  async (cid) => {
    if (cid) {
      await store.loadMessages(cid)
    } else {
      store.newConversation()
    }
  },
  { immediate: true },
)

onMounted(() => store.loadConversations(appId))

function selectConversation(id: string) {
  if (id !== currentId.value) router.push({ query: { c: id } })
}

function startNew() {
  store.newConversation()
  if (queryCid.value) router.push({ query: {} })
}

async function onSend() {
  const text = input.value.trim()
  if (!text || sending.value) return
  input.value = ''
  const wasNew = currentId.value === null
  const cid = await store.send(appId, text)
  if (wasNew) {
    // 新会话拿到 id：写回 URL（replace 不增历史栈）并刷新侧边栏
    await router.replace({ query: { c: cid } })
    await store.loadConversations(appId)
  }
}
</script>

<template>
  <div class="chat">
    <ConversationSidebar
      :conversations="conversations"
      :current-id="currentId"
      @select="selectConversation"
      @new="startNew"
    />
    <div class="chat__main">
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
        <div data-test="chat-input">
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            :disabled="sending"
            placeholder="输入消息，回车或点发送…"
            @keyup.enter.exact.prevent="onSend"
          />
        </div>
        <el-button type="primary" data-test="chat-send" :loading="sending" @click="onSend">
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.chat {
  display: flex;
  height: 100%;

  &__main {
    display: flex;
    flex: 1;
    flex-direction: column;
    gap: 12px;
    padding: 16px;
  }

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

    > [data-test="chat-input"] {
      flex: 1;
    }
  }
}
</style>
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ChatView.spec.ts`
Expected: 全绿（6 passed）。

- [ ] **Step 5: 前端全量回归 + 类型检查 + 构建**

Run: `cd web && pnpm test && pnpm typecheck && pnpm build`
Expected: 测试全绿；`vue-tsc --noEmit` 无报错；`vite build` 成功。

- [ ] **Step 6: 提交**

```bash
git add web/src/views/conversation/ChatView.vue \
        web/src/views/conversation/__tests__/ChatView.spec.ts
git commit -m "feat(web): ChatView 升级两栏——会话列表/切换/新建/刷新恢复（URL query + store）"
```

---

## 收尾（计划执行完成后）

- [ ] 后端全量：`cd server && mvn test`（看 Surefire 计数，含 ModularityTests / LayerRulesTest）。
- [ ] 前端全量：`cd web && pnpm test && pnpm typecheck && pnpm build`。
- [ ] 写本轮自检 `docs/self-check-conversation-multi-turn.md`（沿用单轮自检结构：决策落地表、事务边界保证、契约核对、范围外项、真实模型多轮手验复现步骤），并提交。
- [ ] 真实模型多轮手验（活模型才能证、自动化用 mock 替过）：建可用 chat 应用 → 连发「我叫小明」「我叫什么」，确认第二轮答出「小明」=历史进了 prompt；再开新会话确认不串记忆。结果补进自检文档。

---

## 自检（写计划后对照 spec）

**1. Spec 覆盖**
- 决策1 滑动窗口 N=10 → Task 2（config + readWindow `2N+1`）✓
- 决策2 openTurn 返 TurnContext + invoke 喂窗口 → Task 2 ✓
- 决策3 会话列表 A（列表/新建/切换/恢复，不分页 cap50）→ Task 3（端点）+ Task 5/6/7（前端）✓
- 决策4 标题截断现状 + 前端省略号 → Task 6（`text-overflow: ellipsis`）；后端无改动 ✓
- 决策5 URL query + Pinia + 两栏 → Task 5/6/7 ✓
- 约束6 不破契约（17xxx 不增改 / 无 @Transactional 编排 / messages URL 不变 / 无新 Flyway / Long→string）→ 全程守，Task 3 controller 路由 URL 保持 ✓

**2. 占位符扫描**：无 TBD/TODO；每个代码步骤含完整代码 ✓

**3. 类型一致性**
- `openTurn` 返回 `TurnContext`，service 用 `turn.conversationId()/turn.window()` 一致 ✓
- `invoke(ChatClient, String, List<Message>)` 在 ChatInvoker、ConversationService、ConversationServiceTest 三处签名一致 ✓
- `ConversationView(Long id, String title, OffsetDateTime updateTime)` 后端 record 与前端 `ConversationView{id,title,updateTime}` 字段对齐 ✓
- store actions `loadConversations/loadMessages/newConversation/send` 在 store 实现、store 测试、ChatView 三处一致 ✓
- `ConversationProperties` 在 Task 2 为单参 `(Memory)`，Task 3 扩为 `(Memory, ListProps)`，对应 ConversationStoreTest setUp 两次构造已同步更新 ✓
