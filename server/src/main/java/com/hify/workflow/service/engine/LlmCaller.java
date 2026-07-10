package com.hify.workflow.service.engine;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * ChatClient 的薄适配（仿 conversation 的 ChatInvoker）：同步 call 收敛成 LlmCallResult。
 * 不带 @Transactional——真实外部 IO。韧性（信号量/三层超时/熔断）已在 provider 的 ResilientChatModel 内。
 */
@Service
public class LlmCaller {

    public LlmCallResult call(ChatClient client, String systemPrompt, String userPrompt) {
        ChatClient.ChatClientRequestSpec spec = client.prompt();
        if (StringUtils.hasText(systemPrompt)) {
            spec = spec.system(systemPrompt);
        }
        ChatResponse resp = spec.user(userPrompt).call().chatResponse();
        String text = resp.getResult().getOutput().getText();
        Usage usage = resp.getMetadata().getUsage();
        int promptTokens = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int completionTokens = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        return new LlmCallResult(text, promptTokens, completionTokens);
    }
}
