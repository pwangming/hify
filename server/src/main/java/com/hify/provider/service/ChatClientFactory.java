package com.hify.provider.service;

import com.hify.common.exception.BizException;
import com.hify.infra.crypto.SecretCipher;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * 按 {@code provider.protocol} 构建原始 Spring AI {@link ChatModel}（未包韧性，由 ResilientChatModel 装饰）。
 * 传入单次 RetryTemplate 关掉 Spring AI 自带重试（全链路只允许 Resilience4j 一处重试，见 llm-resilience.md §3）。
 */
@Component
public class ChatClientFactory {

    /** 全库统一向量维度（kb_chunk.embedding vector(1024)，database-standards §2.1）。 */
    public static final int EMBEDDING_DIMENSION = 1024;

    private final SecretCipher cipher;
    private final RetryTemplate noRetryTemplate;

    public ChatClientFactory(SecretCipher cipher, RetryTemplate noRetryTemplate) {
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

    /**
     * 构建原始 EmbeddingModel（未包韧性，由 ResilientEmbeddingModel 装饰）。仅 openai 协议；
     * options 固定 dimensions=1024，支持可变维度的模型自动输出 1024 维。
     */
    public EmbeddingModel buildEmbeddingModel(ModelProvider provider, AiModel model) {
        if (!"openai".equals(provider.getProtocol())) {
            throw new BizException(ProviderError.EMBEDDING_NOT_SUPPORTED);
        }
        String apiKey = cipher.decrypt(provider.getApiKeyCipher());
        OpenAiApi api = buildOpenAiApi(provider, apiKey);
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(model.getModelKey())
                        .dimensions(EMBEDDING_DIMENSION)
                        .build(),
                noRetryTemplate);
    }

    private ChatModel openAi(ModelProvider p, AiModel m, String apiKey) {
        OpenAiApi api = buildOpenAiApi(p, apiKey);
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

    /** baseUrl 归一化：仅去首尾空白与尾部斜杠（spec 决策 2：不做更多防呆，填错由试连接暴露真实错误）。 */
    static String normalizeBaseUrl(String baseUrl) {
        String s = baseUrl.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * openai 协议统一装配（包级可见供测试断言）：显式资源路径 + 完整基址约定（修缮轮拍板，
     * llm-resilience.md §6.1）。baseUrl 照抄厂商文档完整基址（含版本段），此处只拼
     * /chat/completions 与 /embeddings——任意版本前缀（/v1、/api/v3、/api/paas/v4...）的网关均可接入。
     */
    OpenAiApi buildOpenAiApi(ModelProvider p, String apiKey) {
        return OpenAiApi.builder()
                .baseUrl(normalizeBaseUrl(p.getBaseUrl()))
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .embeddingsPath("/embeddings")
                .build();
    }
}
