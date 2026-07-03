package com.hify.provider.service;

import com.hify.common.exception.BizException;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.api.dto.ModelView;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProviderFacadeImpl 单元测试：薄委托，验证透传 ModelQueryService / ResilienceRegistry 的返回。
 */
class ProviderFacadeImplTest {

    private ModelQueryService modelQueryService;
    private ResilienceRegistry resilienceRegistry;
    private EmbeddingSettingService embeddingSettingService;
    private ProviderFacadeImpl facade;

    @BeforeEach
    void setUp() {
        modelQueryService = mock(ModelQueryService.class);
        resilienceRegistry = mock(ResilienceRegistry.class);
        embeddingSettingService = mock(EmbeddingSettingService.class);
        facade = new ProviderFacadeImpl(modelQueryService, resilienceRegistry, embeddingSettingService);
    }

    @Test
    void 透传可用模型() {
        ModelView v = new ModelView(5L, "GPT-4o", "chat", "通义千问");
        when(modelQueryService.findUsableChatModel(5L)).thenReturn(Optional.of(v));

        Optional<ModelView> result = facade.findUsableChatModel(5L);

        assertTrue(result.isPresent());
        assertEquals(5L, result.get().id());
    }

    @Test
    void 透传不可用空() {
        when(modelQueryService.findUsableChatModel(9L)).thenReturn(Optional.empty());
        assertTrue(facade.findUsableChatModel(9L).isEmpty());
    }

    @Test
    void 透传名字映射() {
        when(modelQueryService.getModelNames(java.util.List.of(5L)))
                .thenReturn(java.util.Map.of(5L, "GPT-4o"));
        assertEquals("GPT-4o", facade.getModelNames(java.util.List.of(5L)).get(5L));
    }

    @Test
    void 透传可用id过滤() {
        when(modelQueryService.filterUsableChatModelIds(java.util.List.of(5L)))
                .thenReturn(java.util.Set.of(5L));
        assertTrue(facade.filterUsableChatModelIds(java.util.List.of(5L)).contains(5L));
    }

    @Test
    void getChatClient_委托给registry() {
        ChatClient client = mock(ChatClient.class);
        when(resilienceRegistry.getChatClient(9L)).thenReturn(client);

        assertSame(client, facade.getChatClient(9L));
        verify(resilienceRegistry).getChatClient(9L);
    }

    @Test
    void getEmbeddingModel_未配置_抛12006() {
        when(embeddingSettingService.currentModelId()).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> facade.getEmbeddingModel());
        assertEquals(ProviderError.EMBEDDING_MODEL_NOT_CONFIGURED, ex.errorCode());
    }

    @Test
    void getEmbeddingModel_已配置_委托Registry() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(embeddingSettingService.currentModelId()).thenReturn(6L);
        when(resilienceRegistry.getEmbeddingModel(6L)).thenReturn(model);
        assertSame(model, facade.getEmbeddingModel());
    }
}
