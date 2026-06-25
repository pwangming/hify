package com.hify.provider.service;

import com.hify.provider.api.dto.ModelView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ProviderFacadeImpl 单元测试：薄委托，验证透传 ModelQueryService 的返回。
 */
class ProviderFacadeImplTest {

    private ModelQueryService modelQueryService;
    private ProviderFacadeImpl facade;

    @BeforeEach
    void setUp() {
        modelQueryService = mock(ModelQueryService.class);
        facade = new ProviderFacadeImpl(modelQueryService);
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
}
