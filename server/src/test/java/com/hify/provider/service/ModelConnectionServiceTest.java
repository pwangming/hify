package com.hify.provider.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hify.common.exception.BizException;
import com.hify.provider.api.dto.ModelTestResponse;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
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
        when(chatClient.prompt().user("ping").call().content()).thenReturn("pong");
        when(registry.getChatClient(5L)).thenReturn(chatClient);

        ModelTestResponse resp = service.test(5L);

        assertEquals("pong", resp.sample());
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
}
