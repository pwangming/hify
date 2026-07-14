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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentChatServiceTest {

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
        assertThat(reply.promptTokens()).isEqualTo(3 + 4);
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
        Deque<ChatResponse> script = new ArrayDeque<>();
        for (int i = 0; i < 10; i++) {
            script.add(assistantWithToolCall("http_request", "{}"));
        }
        AgentReply reply = withScript(3, script)
                .run(null, "", List.of(userMsg("loop")), List.of(fakeTool("http_request", "R")));
        assertThat(reply.content()).contains("步数上限");
        assertThat(reply.toolCalls()).hasSize(3);
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

    @Test
    void run重载_每调完一个工具触发一次事件() {
        Deque<ChatResponse> script = new ArrayDeque<>(List.of(
                assistantWithToolCall("http_request", "{\"url\":\"x\"}"),
                finalAnswer("好了")));
        AgentChatService svc = withScript(5, script);
        List<StreamEvent.ToolCall> events = new ArrayList<>();

        AgentReply reply = svc.run(null, "", List.of(userMsg("q")),
                List.of(fakeTool("http_request", "HTTP 200")), events::add);

        assertThat(reply.content()).isEqualTo("好了");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).toolName()).isEqualTo("http_request");
        assertThat(events.get(0).result()).contains("HTTP 200");
        assertThat(events.get(0).ok()).isTrue();
    }

    @Test
    void run重载_未知工具事件ok为false() {
        Deque<ChatResponse> script = new ArrayDeque<>(List.of(
                assistantWithToolCall("ghost", "{}"),
                finalAnswer("x")));
        List<StreamEvent.ToolCall> events = new ArrayList<>();
        withScript(5, script).run(null, "", List.of(userMsg("q")),
                List.of(fakeTool("http_request", "R")), events::add);
        assertThat(events.get(0).ok()).isFalse();
    }

    private static Message userMsg(String content) {
        Message m = new Message();
        m.setRole("user");
        m.setContent(content);
        return m;
    }
}
