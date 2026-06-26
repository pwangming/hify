package com.hify.provider.service;

import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * 按 {@code provider.protocol} 构建原始 Spring AI {@link ChatModel}（未包韧性，由 ResilientChatModel 装饰）。
 * 传入单次 RetryTemplate 关掉 Spring AI 自带重试（全链路只允许 Resilience4j 一处重试，见 llm-resilience.md §3）。
 */
@Component
public class ChatClientFactory {

    private final ApiKeyCipher cipher;
    private final RetryTemplate noRetryTemplate;

    public ChatClientFactory(ApiKeyCipher cipher, RetryTemplate noRetryTemplate) {
        this.cipher = cipher;
        this.noRetryTemplate = noRetryTemplate;
    }

    public ChatModel buildChatModel(ModelProvider provider, AiModel model) {
        String apiKey = cipher.decrypt(provider.getApiKeyCipher());
        return switch (provider.getProtocol()) {
            case "openai" -> openAi(provider, model, apiKey);
            case "anthropic" -> anthropic(provider, model, apiKey);
            default -> throw new IllegalArgumentException("不支持的协议: " + provider.getProtocol());
        };
    }

    private ChatModel openAi(ModelProvider p, AiModel m, String apiKey) {
        OpenAiApi api = OpenAiApi.builder().baseUrl(p.getBaseUrl()).apiKey(apiKey).build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(m.getModelKey()).build())
                .retryTemplate(noRetryTemplate)
                .build();
    }

    private ChatModel anthropic(ModelProvider p, AiModel m, String apiKey) {
        AnthropicApi api = AnthropicApi.builder().baseUrl(p.getBaseUrl()).apiKey(apiKey).build();
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder().model(m.getModelKey()).build())
                .retryTemplate(noRetryTemplate)
                .build();
    }
}
