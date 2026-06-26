package com.hify.provider.service;

import com.hify.provider.api.dto.ModelView;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    private ProviderFacadeImpl facade;

    @BeforeEach
    void setUp() {
        modelQueryService = mock(ModelQueryService.class);
        resilienceRegistry = mock(ResilienceRegistry.class);
        facade = new ProviderFacadeImpl(modelQueryService, resilienceRegistry);
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
}
