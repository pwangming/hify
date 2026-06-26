package com.hify.provider.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.constant.ModelType;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.dto.CreateModelRequest;
import com.hify.provider.dto.ModelResponse;
import com.hify.provider.dto.UpdateModelRequest;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiModelService 单元测试：mock AiModelMapper + ModelProviderMapper，不连库。
 * 覆盖创建（含 embedding 协议守卫、重名、provider 不存在）/更新/启停幂等/删除/列表。
 */
class AiModelServiceTest {

    private AiModelMapper modelMapper;
    private ModelProviderMapper providerMapper;
    private com.hify.provider.service.resilience.ResilienceRegistry registry;
    private AiModelService service;

    @BeforeEach
    void setUp() {
        modelMapper = mock(AiModelMapper.class);
        providerMapper = mock(ModelProviderMapper.class);
        registry = mock(com.hify.provider.service.resilience.ResilienceRegistry.class);
        service = new AiModelService(modelMapper, providerMapper, registry);
    }

    private ModelProvider provider(long id, String protocol) {
        ModelProvider p = new ModelProvider();
        p.setId(id);
        p.setProtocol(protocol);
        return p;
    }

    private AiModel model(long id, long providerId, String type, String status) {
        AiModel m = new AiModel();
        m.setId(id);
        m.setProviderId(providerId);
        m.setType(type);
        m.setName("旧名");
        m.setModelKey("old-key");
        m.setStatus(status);
        m.setCreateTime(OffsetDateTime.now());
        return m;
    }

    @Test
    void 创建chat_openai下_写库且状态默认启用() {
        when(providerMapper.selectById(1L)).thenReturn(provider(1L, "openai"));
        when(modelMapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<AiModel> captor = ArgumentCaptor.forClass(AiModel.class);

        ModelResponse resp = service.create(1L, new CreateModelRequest("chat", "GPT-4o", "gpt-4o"));

        verify(modelMapper).insert(captor.capture());
        AiModel saved = captor.getValue();
        assertEquals(1L, saved.getProviderId());
        assertEquals("chat", saved.getType());
        assertEquals("gpt-4o", saved.getModelKey());
        assertEquals(ProviderStatus.ENABLED.value(), saved.getStatus());
        assertEquals("GPT-4o", resp.name());
    }

    @Test
    void 创建embedding_openai下_成功() {
        when(providerMapper.selectById(1L)).thenReturn(provider(1L, "openai"));
        when(modelMapper.selectCount(any())).thenReturn(0L);

        ModelResponse resp = service.create(1L,
                new CreateModelRequest("embedding", "向量", "text-embedding-3-small"));

        assertEquals("embedding", resp.type());
    }

    @Test
    void 创建embedding_anthropic下_抛12001() {
        when(providerMapper.selectById(2L)).thenReturn(provider(2L, "anthropic"));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(2L, new CreateModelRequest("embedding", "向量", "x")));
        assertEquals(ProviderError.EMBEDDING_NOT_SUPPORTED, ex.errorCode());
        verify(modelMapper, never()).insert(any(AiModel.class));
    }

    @Test
    void 创建chat_anthropic下_成功() {
        when(providerMapper.selectById(2L)).thenReturn(provider(2L, "anthropic"));
        when(modelMapper.selectCount(any())).thenReturn(0L);

        ModelResponse resp = service.create(2L,
                new CreateModelRequest("chat", "Claude", "claude-sonnet-4-6"));

        assertEquals("chat", resp.type());
    }

    @Test
    void 创建_provider不存在_抛NOT_FOUND() {
        when(providerMapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.create(99L, new CreateModelRequest("chat", "x", "x")));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 创建_同供应商下modelKey重复_抛CONFLICT() {
        when(providerMapper.selectById(1L)).thenReturn(provider(1L, "openai"));
        when(modelMapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> service.create(1L, new CreateModelRequest("chat", "x", "gpt-4o")));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 创建_并发命中唯一索引_转CONFLICT() {
        when(providerMapper.selectById(1L)).thenReturn(provider(1L, "openai"));
        when(modelMapper.selectCount(any())).thenReturn(0L);
        when(modelMapper.insert(any(AiModel.class))).thenThrow(new DuplicateKeyException("dup"));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(1L, new CreateModelRequest("chat", "x", "gpt-4o")));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 更新_改名与标识_成功() {
        when(modelMapper.selectById(5L)).thenReturn(model(5L, 1L, "chat", ProviderStatus.ENABLED.value()));
        when(modelMapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<AiModel> captor = ArgumentCaptor.forClass(AiModel.class);

        service.update(5L, new UpdateModelRequest("新名", "gpt-4o-mini"));

        verify(modelMapper).updateById(captor.capture());
        assertEquals("新名", captor.getValue().getName());
        assertEquals("gpt-4o-mini", captor.getValue().getModelKey());
    }

    @Test
    void 更新_不存在_抛NOT_FOUND() {
        when(modelMapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.update(99L, new UpdateModelRequest("x", "x")));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 启用_已启用_幂等不写库() {
        when(modelMapper.selectById(5L)).thenReturn(model(5L, 1L, "chat", ProviderStatus.ENABLED.value()));

        service.enable(5L);

        verify(modelMapper, never()).updateById(any(AiModel.class));
    }

    @Test
    void 禁用_启用态_写库为disabled() {
        when(modelMapper.selectById(5L)).thenReturn(model(5L, 1L, "chat", ProviderStatus.ENABLED.value()));
        ArgumentCaptor<AiModel> captor = ArgumentCaptor.forClass(AiModel.class);

        service.disable(5L);

        verify(modelMapper).updateById(captor.capture());
        assertEquals(ProviderStatus.DISABLED.value(), captor.getValue().getStatus());
    }

    @Test
    void 启停_不存在_抛NOT_FOUND() {
        when(modelMapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.enable(99L));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 删除_走软删() {
        service.delete(5L);
        verify(modelMapper).deleteById(5L);
    }

    @Test
    void 按供应商列表_投影() {
        when(modelMapper.selectList(any())).thenReturn(List.of(
                model(1L, 1L, "chat", ProviderStatus.ENABLED.value()),
                model(2L, 1L, "embedding", ProviderStatus.DISABLED.value())));

        List<ModelResponse> list = service.listByProvider(1L);

        assertEquals(2, list.size());
        assertEquals("chat", list.get(0).type());
        assertEquals(ProviderStatus.DISABLED.value(), list.get(1).status());
    }

    @Test
    void 禁用模型后_失效该模型缓存() {
        when(modelMapper.selectById(5L)).thenReturn(model(5L, 1L, "chat", ProviderStatus.ENABLED.value()));
        service.disable(5L);
        verify(registry).invalidateModel(5L);
    }
}
