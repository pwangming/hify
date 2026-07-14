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
import java.util.function.Consumer;
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
        return run(chatClient, systemPrompt, window, toolCallbacks, tc -> {});
    }

    public AgentReply run(ChatClient chatClient, String systemPrompt, List<Message> window,
                          List<ToolCallback> toolCallbacks,
                          Consumer<StreamEvent.ToolCall> onToolCall) {
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
                boolean ok;
                if (cb == null) {
                    result = "错误：工具不存在：" + call.name();
                    ok = false;
                } else {
                    try {
                        result = cb.call(call.arguments());
                        ok = true;
                    } catch (RuntimeException ex) {
                        result = "错误：工具执行失败：" + ex.getMessage();
                        ok = false;
                    }
                }
                trace.add(new MessageToolCall(call.name(), call.arguments(), result));
                onToolCall.accept(new StreamEvent.ToolCall(call.name(), call.arguments(), result, ok));
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
