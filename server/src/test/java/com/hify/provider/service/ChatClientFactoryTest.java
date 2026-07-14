package com.hify.provider.service;

import com.hify.common.exception.BizException;
import com.hify.infra.crypto.SecretCipher;
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
import org.springframework.ai.openai.api.OpenAiApi;
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

    private SecretCipher cipher;
    private ChatClientFactory factory;

    @BeforeEach
    void setUp() {
        cipher = mock(SecretCipher.class);
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

    @Test
    void normalizeBaseUrl_去尾部斜杠_单个多个与首尾空白() {
        assertEquals("https://a.com/v1", ChatClientFactory.normalizeBaseUrl("https://a.com/v1"));
        assertEquals("https://a.com/v1", ChatClientFactory.normalizeBaseUrl("https://a.com/v1/"));
        assertEquals("https://a.com/v1", ChatClientFactory.normalizeBaseUrl("https://a.com/v1//"));
        assertEquals("https://a.com/v1beta/openai",
                ChatClientFactory.normalizeBaseUrl(" https://a.com/v1beta/openai/ "));
    }

    @Test
    void buildOpenAiApi_显式资源路径_任意版本前缀基址可接入() throws Exception {
        ModelProvider p = provider("openai");
        p.setBaseUrl("https://ark.cn-beijing.volces.com/api/v3/");
        OpenAiApi api = factory.buildOpenAiApi(p, "sk-x");
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", baseUrlOf(api));
        assertEquals("/chat/completions", stringField(api, "completionsPath"));
        assertEquals("/embeddings", stringField(api, "embeddingsPath"));
    }

    // Spring AI 1.0.1 的 getter/字段为包私有——反射断言装配结果；升级 Spring AI 若更名，此测试显式红。
    private static String stringField(OpenAiApi api, String name) throws Exception {
        java.lang.reflect.Field f = OpenAiApi.class.getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(api);
    }

    private static String baseUrlOf(OpenAiApi api) throws Exception {
        java.lang.reflect.Method m = OpenAiApi.class.getDeclaredMethod("getBaseUrl");
        m.setAccessible(true);
        return (String) m.invoke(api);
    }
}
