package com.hify.provider.service;

import com.hify.provider.api.dto.ModelView;
import com.hify.provider.constant.ModelType;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ModelQueryService 单元测试：mock AiModelMapper + ModelProviderMapper，不连库。
 * 覆盖「可用」连带供应商启用状态的判定（spec §决策3）：模型 enabled + type=chat + 供应商 enabled。
 */
class ModelQueryServiceTest {

    private AiModelMapper modelMapper;
    private ModelProviderMapper providerMapper;
    private ModelQueryService service;

    @BeforeEach
    void setUp() {
        modelMapper = mock(AiModelMapper.class);
        providerMapper = mock(ModelProviderMapper.class);
        service = new ModelQueryService(modelMapper, providerMapper);
    }

    private AiModel model(long id, long providerId, String type, String status) {
        AiModel m = new AiModel();
        m.setId(id);
        m.setProviderId(providerId);
        m.setType(type);
        m.setName("GPT-4o");
        m.setModelKey("gpt-4o");
        m.setStatus(status);
        return m;
    }

    private ModelProvider provider(long id, String name, String status) {
        ModelProvider p = new ModelProvider();
        p.setId(id);
        p.setName(name);
        p.setStatus(status);
        return p;
    }

    // ---- findUsableChatModel ----

    @Test
    void 可用_模型chat启用_供应商启用_返回ModelView() {
        when(modelMapper.selectById(5L)).thenReturn(model(5L, 1L, "chat", "enabled"));
        when(providerMapper.selectById(1L)).thenReturn(provider(1L, "通义千问", "enabled"));

        Optional<ModelView> v = service.findUsableChatModel(5L);

        assertTrue(v.isPresent());
        assertEquals(5L, v.get().id());
        assertEquals("GPT-4o", v.get().name());
        assertEquals("chat", v.get().type());
        assertEquals("通义千问", v.get().providerName());
    }

    @Test
    void 不可用_模型不存在_空() {
        when(modelMapper.selectById(5L)).thenReturn(null);
        assertTrue(service.findUsableChatModel(5L).isEmpty());
    }

    @Test
    void 不可用_模型已停用_空() {
        when(modelMapper.selectById(5L)).thenReturn(model(5L, 1L, "chat", "disabled"));
        assertTrue(service.findUsableChatModel(5L).isEmpty());
    }

    @Test
    void 不可用_模型非chat_空且不查供应商() {
        when(modelMapper.selectById(5L)).thenReturn(model(5L, 1L, "embedding", "enabled"));
        assertTrue(service.findUsableChatModel(5L).isEmpty());
        verify(providerMapper, never()).selectById(any());
    }

    @Test
    void 不可用_供应商已停用_空() {
        when(modelMapper.selectById(5L)).thenReturn(model(5L, 1L, "chat", "enabled"));
        when(providerMapper.selectById(1L)).thenReturn(provider(1L, "通义千问", "disabled"));
        assertTrue(service.findUsableChatModel(5L).isEmpty());
    }

    // ---- listUsableChatModels ----

    @Test
    void 列表_过滤掉供应商停用的模型() {
        when(modelMapper.selectList(any())).thenReturn(List.of(
                model(5L, 1L, "chat", "enabled"),   // 供应商 1 启用 → 保留
                model(6L, 2L, "chat", "enabled")));  // 供应商 2 停用 → 过滤
        when(providerMapper.selectBatchIds(any())).thenReturn(List.of(
                provider(1L, "通义千问", "enabled"),
                provider(2L, "Claude海外", "disabled")));

        List<ModelView> list = service.listUsableChatModels("chat");

        assertEquals(1, list.size());
        assertEquals(5L, list.get(0).id());
        assertEquals("通义千问", list.get(0).providerName());
    }

    @Test
    void 列表_无可用_返回空列表非null() {
        when(modelMapper.selectList(any())).thenReturn(List.of());
        List<ModelView> list = service.listUsableChatModels("chat");
        assertTrue(list.isEmpty());
        // 无模型时不应再查供应商
        verify(providerMapper, never()).selectBatchIds(any());
    }

    @Test
    void 列表_type为空按chat兜底() {
        when(modelMapper.selectList(any())).thenReturn(List.of());
        service.listUsableChatModels(null);
        // 不抛异常即可（兜底为 chat），返回空
        assertTrue(service.listUsableChatModels(null).isEmpty());
    }

    // 常量自证，避免硬编码漂移
    @Test
    void 常量一致性() {
        assertEquals("chat", ModelType.CHAT.value());
        assertEquals("enabled", ProviderStatus.ENABLED.value());
    }
}
