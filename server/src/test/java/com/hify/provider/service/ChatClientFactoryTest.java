package com.hify.provider.service;

import com.hify.common.exception.BizException;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.retry.support.RetryTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatClientFactory 单元测试：按协议构建原始 ChatModel，且 decrypt 被调用。不连真实 LLM。
 */
class ChatClientFactoryTest {

    private ApiKeyCipher cipher;
    private ChatClientFactory factory;

    @BeforeEach
    void setUp() {
        cipher = mock(ApiKeyCipher.class);
        when(cipher.decrypt(anyString())).thenReturn("sk-real");
        factory = new ChatClientFactory(cipher, RetryTemplate.builder().maxAttempts(1).build());
    }

    private ModelProvider provider(String protocol) {
        ModelProvider p = new ModelProvider();
        p.setId(1L);
        p.setProtocol(protocol);
        p.setBaseUrl("https://api.example.com");
        p.setApiKeyCipher("CIPHER");
        p.setConnectTimeoutSec(5);
        return p;
    }

    private AiModel model() {
        AiModel m = new AiModel();
        m.setId(9L);
        m.setProviderId(1L);
        m.setModelKey("gpt-4o");
        return m;
    }

    @Test
    void openai协议_建OpenAiChatModel_且解密被调() {
        ChatModel m = factory.buildChatModel(provider("openai"), model());
        assertInstanceOf(OpenAiChatModel.class, m);
        verify(cipher).decrypt("CIPHER");
    }

    @Test
    void anthropic协议_建AnthropicChatModel() {
        ChatModel m = factory.buildChatModel(provider("anthropic"), model());
        assertInstanceOf(AnthropicChatModel.class, m);
    }

    @Test
    void 未知协议_抛异常() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.buildChatModel(provider("gemini-native"), model()));
    }

    @Test
    void buildEmbeddingModel_openai协议_返回OpenAiEmbeddingModel() {
        ModelProvider p = provider("openai");
        p.setBaseUrl("https://api.example.com");
        p.setApiKeyCipher("cipher-text");
        AiModel m = model();
        m.setModelKey("text-embedding-v4");
        when(cipher.decrypt("cipher-text")).thenReturn("sk-plain");

        EmbeddingModel result = factory.buildEmbeddingModel(p, m);

        assertInstanceOf(OpenAiEmbeddingModel.class, result);
    }

    @Test
    void buildEmbeddingModel_anthropic协议_抛12001() {
        ModelProvider p = provider("anthropic");
        BizException ex = assertThrows(BizException.class,
                () -> factory.buildEmbeddingModel(p, new AiModel()));
        assertEquals(ProviderError.EMBEDDING_NOT_SUPPORTED, ex.errorCode());
    }
}
