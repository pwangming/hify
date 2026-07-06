package com.hify.provider.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.api.dto.ModelTestResponse;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.dto.ProviderTestResponse;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelConnectionServiceTest {

    private ResilienceRegistry registry;
    private AiModelMapper modelMapper;
    private ModelProviderMapper providerMapper;
    private ModelConnectionService service;

    @BeforeEach
    void setUp() {
        // Lambda Wrapper 需要 TableInfo（模仿 EmbeddingSettingServiceTest）
        for (Class<?> c : new Class<?>[]{AiModel.class, ModelProvider.class}) {
            if (TableInfoHelper.getTableInfo(c) == null) {
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), c);
            }
        }
        registry = mock(ResilienceRegistry.class);
        modelMapper = mock(AiModelMapper.class);
        providerMapper = mock(ModelProviderMapper.class);
        service = new ModelConnectionService(registry, modelMapper, providerMapper);
    }

    private AiModel model(long id, String type) {
        AiModel m = new AiModel();
        m.setId(id);
        m.setProviderId(1L);
        m.setType(type);
        m.setName(type + "-模型");
        m.setStatus("enabled");
        return m;
    }

    @Test
    void test_chat模型_ping聊天返回样例() {
        when(modelMapper.selectById(5L)).thenReturn(model(5L, "chat"));
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().options(any(ChatOptions.class)).user("ping").call().content())
                .thenReturn("pong");
        when(registry.getChatClient(5L)).thenReturn(chatClient);

        ModelTestResponse resp = service.test(5L);

        assertEquals("pong", resp.sample());
    }

    @Test
    void test_chat测试限制输出token上限_防慢模型耗尽超时() {
        when(modelMapper.selectById(5L)).thenReturn(model(5L, "chat"));
        // 不打桩返回值（deep stub 默认 null 即可），否则 when(...) 链本身会多记一次 options 调用
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(registry.getChatClient(5L)).thenReturn(chatClient);

        service.test(5L);

        ArgumentCaptor<ChatOptions> captor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(chatClient.prompt()).options(captor.capture());
        assertEquals(16, captor.getValue().getMaxTokens());
    }

    @Test
    void test_embedding模型_转向量返回维度样例() {
        when(modelMapper.selectById(6L)).thenReturn(model(6L, "embedding"));
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[1024]);
        when(registry.getEmbeddingModel(6L)).thenReturn(embeddingModel);

        ModelTestResponse resp = service.test(6L);

        assertEquals("已返回 1024 维向量", resp.sample());
    }

    @Test
    void test_模型不存在_MODEL_NOT_USABLE() {
        when(modelMapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.test(99L));
        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
    }

    private ModelProvider provider(String status) {
        ModelProvider p = new ModelProvider();
        p.setId(1L);
        p.setName("通义");
        p.setStatus(status);
        return p;
    }

    @Test
    void testProvider_优先挑chat_成功落库ok() {
        when(providerMapper.selectById(1L)).thenReturn(provider("enabled"));
        when(modelMapper.selectList(any())).thenReturn(
                java.util.List.of(model(6L, "embedding"), model(5L, "chat")));
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().options(any(ChatOptions.class)).user("ping").call().content())
                .thenReturn("pong");
        when(registry.getChatClient(5L)).thenReturn(chatClient);

        ProviderTestResponse resp = service.testProvider(1L);

        assertEquals("chat-模型", resp.modelName());
        assertEquals("pong", resp.sample());
        ArgumentCaptor<LambdaUpdateWrapper<ModelProvider>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(providerMapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("last_test_status"));
    }

    @Test
    void testProvider_仅embedding_用embedding测() {
        when(providerMapper.selectById(1L)).thenReturn(provider("enabled"));
        when(modelMapper.selectList(any())).thenReturn(java.util.List.of(model(6L, "embedding")));
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[1024]);
        when(registry.getEmbeddingModel(6L)).thenReturn(embeddingModel);

        ProviderTestResponse resp = service.testProvider(1L);

        assertEquals("已返回 1024 维向量", resp.sample());
    }

    @Test
    void testProvider_调用失败_落库fail后原异常继续抛() {
        when(providerMapper.selectById(1L)).thenReturn(provider("enabled"));
        when(modelMapper.selectList(any())).thenReturn(java.util.List.of(model(5L, "chat")));
        when(registry.getChatClient(5L))
                .thenThrow(new BizException(ProviderError.PROVIDER_UNAVAILABLE));

        BizException ex = assertThrows(BizException.class, () -> service.testProvider(1L));

        assertEquals(ProviderError.PROVIDER_UNAVAILABLE, ex.errorCode());
        verify(providerMapper).update(isNull(), any()); // 失败也落库
    }

    @Test
    void testProvider_失败_落库记录cause链底层真实原因() {
        when(providerMapper.selectById(1L)).thenReturn(provider("enabled"));
        when(modelMapper.selectList(any())).thenReturn(java.util.List.of(model(5L, "chat")));
        when(registry.getChatClient(5L)).thenThrow(new BizException(
                ProviderError.PROVIDER_UNAVAILABLE,
                ProviderError.PROVIDER_UNAVAILABLE.defaultMessage(),
                new SocketTimeoutException("Read timed out")));

        assertThrows(BizException.class, () -> service.testProvider(1L));

        ArgumentCaptor<LambdaUpdateWrapper<ModelProvider>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(providerMapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getParamNameValuePairs().values().stream()
                        .anyMatch(v -> String.valueOf(v).contains("Read timed out")),
                "last_test_error 应包含底层真实原因，而非只有 12003 统一文案");
    }

    @Test
    void testProvider_无启用模型_12002且不落库() {
        when(providerMapper.selectById(1L)).thenReturn(provider("enabled"));
        when(modelMapper.selectList(any())).thenReturn(java.util.List.of());

        BizException ex = assertThrows(BizException.class, () -> service.testProvider(1L));

        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
        verify(providerMapper, never()).update(any(), any());
    }

    @Test
    void testProvider_供应商不存在或禁用() {
        when(providerMapper.selectById(9L)).thenReturn(null);
        assertEquals(CommonError.NOT_FOUND,
                assertThrows(BizException.class, () -> service.testProvider(9L)).errorCode());

        when(providerMapper.selectById(1L)).thenReturn(provider("disabled"));
        assertEquals(ProviderError.MODEL_NOT_USABLE,
                assertThrows(BizException.class, () -> service.testProvider(1L)).errorCode());
    }
}
