package com.hify.provider.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.config.ProviderCryptoProperties;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.dto.CreateProviderRequest;
import com.hify.provider.dto.ProviderResponse;
import com.hify.provider.dto.UpdateProviderRequest;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.ModelProviderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProviderService 单元测试：mock ModelProviderMapper，用真实 ApiKeyCipher，不连库。
 * 覆盖创建/更新（含 apiKey 留空保留）/列表投影/启停幂等/删除幂等/重名与不存在分支。
 */
class ProviderServiceTest {

    private ModelProviderMapper mapper;
    private ApiKeyCipher cipher;
    private ProviderService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ModelProviderMapper.class);
        ProviderCryptoProperties props = new ProviderCryptoProperties();
        props.setMasterKey("unit-test-master-key");
        cipher = new ApiKeyCipher(props);
        service = new ProviderService(mapper, cipher);
    }

    private CreateProviderRequest createReq() {
        return new CreateProviderRequest(
                "通义-生产", "openai",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-abcdef123456");
    }

    private ModelProvider stored(long id, String name, String status) {
        ModelProvider e = new ModelProvider();
        e.setId(id);
        e.setName(name);
        e.setProtocol("openai");
        e.setBaseUrl("https://api.openai.com/v1");
        e.setApiKeyCipher("OLD-CIPHER");
        e.setApiKeyTail("3456");
        e.setStatus(status);
        e.setCreateTime(OffsetDateTime.now());
        return e;
    }

    @Test
    void 创建_密钥被加密_写后4位tail_状态默认启用() {
        when(mapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<ModelProvider> captor = ArgumentCaptor.forClass(ModelProvider.class);

        ProviderResponse resp = service.create(createReq());

        verify(mapper).insert(captor.capture());
        ModelProvider saved = captor.getValue();
        assertNotEquals("sk-abcdef123456", saved.getApiKeyCipher());            // 不是明文
        assertEquals("sk-abcdef123456", cipher.decrypt(saved.getApiKeyCipher())); // 可解回
        assertEquals("3456", saved.getApiKeyTail());                            // 后 4 位
        assertEquals(ProviderStatus.ENABLED.value(), saved.getStatus());
        assertEquals("3456", resp.apiKeyTail());
    }

    @Test
    void 创建_重名预检_抛CONFLICT() {
        when(mapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class, () -> service.create(createReq()));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 创建_并发命中唯一索引_转CONFLICT() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(ModelProvider.class))).thenThrow(new DuplicateKeyException("dup"));

        BizException ex = assertThrows(BizException.class, () -> service.create(createReq()));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 更新_apiKey留空_保留原密文与tail_其余字段更新() {
        ModelProvider existing = stored(5L, "old-name", ProviderStatus.ENABLED.value());
        when(mapper.selectById(5L)).thenReturn(existing);
        when(mapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<ModelProvider> captor = ArgumentCaptor.forClass(ModelProvider.class);

        service.update(5L, new UpdateProviderRequest(
                "new-name", "anthropic", "https://api.anthropic.com", ""));

        verify(mapper).updateById(captor.capture());
        ModelProvider saved = captor.getValue();
        assertEquals("OLD-CIPHER", saved.getApiKeyCipher()); // 密文未动
        assertEquals("3456", saved.getApiKeyTail());          // tail 未动
        assertEquals("new-name", saved.getName());            // 其余字段更新
        assertEquals("anthropic", saved.getProtocol());
    }

    @Test
    void 更新_apiKey非空_重新加密并刷新tail() {
        ModelProvider existing = stored(5L, "x", ProviderStatus.ENABLED.value());
        when(mapper.selectById(5L)).thenReturn(existing);
        when(mapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<ModelProvider> captor = ArgumentCaptor.forClass(ModelProvider.class);

        service.update(5L, new UpdateProviderRequest(
                "x", "openai", "https://api.openai.com/v1", "sk-newkey9999"));

        verify(mapper).updateById(captor.capture());
        ModelProvider saved = captor.getValue();
        assertEquals("sk-newkey9999", cipher.decrypt(saved.getApiKeyCipher()));
        assertEquals("9999", saved.getApiKeyTail());
    }

    @Test
    void 更新_不存在_抛NOT_FOUND() {
        when(mapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.update(99L,
                new UpdateProviderRequest("x", "openai", "https://api.openai.com/v1", "")));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 列表_投影出tail与status_不暴露密文字段() {
        when(mapper.selectList(any())).thenReturn(List.of(
                stored(1L, "a", ProviderStatus.ENABLED.value()),
                stored(2L, "b", ProviderStatus.DISABLED.value())));

        List<ProviderResponse> list = service.list();

        assertEquals(2, list.size());
        assertEquals("3456", list.get(0).apiKeyTail());
        assertEquals(ProviderStatus.DISABLED.value(), list.get(1).status());
        // ProviderResponse 无 cipher/apiKey 字段，密文不可能出响应（编译期保证）
    }

    @Test
    void 启用_已启用_幂等不写库() {
        when(mapper.selectById(1L)).thenReturn(stored(1L, "a", ProviderStatus.ENABLED.value()));

        service.enable(1L);

        verify(mapper, never()).updateById(any(ModelProvider.class));
    }

    @Test
    void 禁用_启用态_写库为disabled() {
        when(mapper.selectById(1L)).thenReturn(stored(1L, "a", ProviderStatus.ENABLED.value()));
        ArgumentCaptor<ModelProvider> captor = ArgumentCaptor.forClass(ModelProvider.class);

        service.disable(1L);

        verify(mapper).updateById(captor.capture());
        assertEquals(ProviderStatus.DISABLED.value(), captor.getValue().getStatus());
    }

    @Test
    void 启停_不存在_抛NOT_FOUND() {
        when(mapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.enable(99L));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 删除_走软删() {
        service.delete(5L);
        verify(mapper).deleteById(5L);
    }

    @Test
    void 删除_不存在_幂等不抛() {
        service.delete(99L); // 不抛即通过（deleteById 对已不存在的也算成功）
        verify(mapper).deleteById(99L);
    }
}
