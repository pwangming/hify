package com.hify.conversation.service;

import com.hify.conversation.constant.MessageRole;
import com.hify.conversation.entity.Message;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI ChatClient 的薄适配层（仿 provider 的 ModelConnectionService）：把流式 API 收敛成 LlmReply。
 * 不带 @Transactional——这里发生真实外部 IO。systemPrompt 空白则不加 system 段。
 * toMessages 为纯映射方法（领域消息窗口→Spring AI 消息），有单元测试（ChatInvokerTest）；
 * invoke 涉及真实模型调用（外部 IO），不做单元测试，由收尾自检手验（见 self-check）。
 */
@Service
public class ChatInvoker {

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
}
