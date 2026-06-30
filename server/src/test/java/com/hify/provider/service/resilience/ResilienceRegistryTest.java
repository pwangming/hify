package com.hify.provider.service.resilience;

import com.hify.common.exception.BizException;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import com.hify.provider.service.ChatClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilienceRegistryTest {

    private AiModelMapper modelMapper;
    private ModelProviderMapper providerMapper;
    private ChatClientFactory factory;
    private ResilienceRegistry registry;

    @BeforeEach
    void setUp() {
        modelMapper = mock(AiModelMapper.class);
        providerMapper = mock(ModelProviderMapper.class);
        factory = mock(ChatClientFactory.class);
        ChatModel stub = prompt -> new ChatResponse(java.util.List.of());
        when(factory.buildChatModel(any(), any())).thenReturn(stub);
        registry = new ResilienceRegistry(modelMapper, providerMapper, factory,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    private AiModel model(String type, String status) {
        AiModel m = new AiModel();
        m.setId(9L); m.setProviderId(1L); m.setType(type); m.setModelKey("gpt-4o"); m.setStatus(status);
        return m;
    }

    private ModelProvider provider(String status) {
        ModelProvider p = new ModelProvider();
        p.setId(1L); p.setProtocol("openai"); p.setStatus(status);
        p.setMaxConcurrency(2); p.setRetryMaxAttempts(3); p.setCbFailureRate(50);
        p.setCbWaitOpenSec(30); p.setResponseTimeoutSec(120);
        p.setFirstTokenTimeoutSec(30);
        p.setTokenGapTimeoutSec(60);
        p.setStreamMaxDurationSec(600);
        return p;
    }

    private void usable() {
        when(modelMapper.selectById(9L)).thenReturn(model("chat", ProviderStatus.ENABLED.value()));
        when(providerMapper.selectById(1L)).thenReturn(provider(ProviderStatus.ENABLED.value()));
    }

    @Test
    void 可用模型_返回ChatClient() {
        usable();
        ChatClient c = registry.getChatClient(9L);
        assertNotNull(c);
    }

    @Test
    void 同modelId两次_命中缓存_工厂只建一次() {
        usable();
        registry.getChatClient(9L);
        registry.getChatClient(9L);
        verify(factory, times(1)).buildChatModel(any(), any());
    }

    @Test
    void 模型不存在_抛12002() {
        when(modelMapper.selectById(9L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> registry.getChatClient(9L));
        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
    }

    @Test
    void 模型停用_抛12002() {
        when(modelMapper.selectById(9L)).thenReturn(model("chat", ProviderStatus.DISABLED.value()));
        assertThrows(BizException.class, () -> registry.getChatClient(9L));
    }

    @Test
    void 非chat_抛12002() {
        when(modelMapper.selectById(9L)).thenReturn(model("embedding", ProviderStatus.ENABLED.value()));
        assertThrows(BizException.class, () -> registry.getChatClient(9L));
    }

    @Test
    void 供应商停用_抛12002() {
        when(modelMapper.selectById(9L)).thenReturn(model("chat", ProviderStatus.ENABLED.value()));
        when(providerMapper.selectById(1L)).thenReturn(provider(ProviderStatus.DISABLED.value()));
        assertThrows(BizException.class, () -> registry.getChatClient(9L));
    }

    @Test
    void invalidateModel后_重建() {
        usable();
        registry.getChatClient(9L);
        registry.invalidateModel(9L);
        registry.getChatClient(9L);
        verify(factory, times(2)).buildChatModel(any(), any());
    }

    @Test
    void invalidate供应商后_名下client重建() {
        usable();
        registry.getChatClient(9L);
        registry.invalidate(1L);
        registry.getChatClient(9L);
        verify(factory, times(2)).buildChatModel(any(), any());
    }
}
