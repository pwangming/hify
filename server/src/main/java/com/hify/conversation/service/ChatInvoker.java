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
