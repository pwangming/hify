package com.hify.provider.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.dto.EmbeddingSettingResponse;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.entity.SystemSetting;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import com.hify.provider.mapper.SystemSettingMapper;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingSettingServiceTest {

    private SystemSettingMapper settingMapper;
    private AiModelMapper modelMapper;
    private ModelProviderMapper providerMapper;
    private ResilienceRegistry registry;
    private EmbeddingModel embeddingModel;
    private EmbeddingSettingService service;

    @BeforeEach
    void setUp() {
        if (TableInfoHelper.getTableInfo(SystemSetting.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SystemSetting.class);
        }
        settingMapper = mock(SystemSettingMapper.class);
        modelMapper = mock(AiModelMapper.class);
        providerMapper = mock(ModelProviderMapper.class);
        registry = mock(ResilienceRegistry.class);
        embeddingModel = mock(EmbeddingModel.class);
        service = new EmbeddingSettingService(settingMapper, modelMapper, providerMapper, registry);
    }

    private AiModel usableEmbeddingModel() {
        AiModel m = new AiModel();
        m.setId(6L);
        m.setProviderId(1L);
        m.setType("embedding");
        m.setName("千问 v4");
        m.setStatus("enabled");
        return m;
    }

    private ModelProvider enabledProvider() {
        ModelProvider p = new ModelProvider();
        p.setId(1L);
        p.setStatus("enabled");
        return p;
    }

    private SystemSetting settingRow(String value) {
        SystemSetting s = new SystemSetting();
        s.setId(1L);
        s.setSettingKey("embedding_model_id");
        s.setSettingValue(value);
        return s;
    }

    @Test
    void get_未配置_两字段均null() {
        when(settingMapper.selectOne(any())).thenReturn(null);
        EmbeddingSettingResponse resp = service.get();
        assertNull(resp.modelId());
        assertNull(resp.modelName());
    }

    @Test
    void get_已配置_回显id与模型名() {
        when(settingMapper.selectOne(any())).thenReturn(settingRow("6"));
        when(modelMapper.selectById(6L)).thenReturn(usableEmbeddingModel());
        EmbeddingSettingResponse resp = service.get();
        assertEquals(6L, resp.modelId());
        assertEquals("千问 v4", resp.modelName());
    }

    @Test
    void save_模型不存在_NOT_FOUND() {
        when(modelMapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.save(99L));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void save_type是chat_MODEL_NOT_USABLE() {
        AiModel chat = usableEmbeddingModel();
        chat.setType("chat");
        when(modelMapper.selectById(6L)).thenReturn(chat);
        BizException ex = assertThrows(BizException.class, () -> service.save(6L));
        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
    }

    @Test
    void save_供应商停用_MODEL_NOT_USABLE() {
        when(modelMapper.selectById(6L)).thenReturn(usableEmbeddingModel());
        ModelProvider disabled = enabledProvider();
        disabled.setStatus("disabled");
        when(providerMapper.selectById(1L)).thenReturn(disabled);
        BizException ex = assertThrows(BizException.class, () -> service.save(6L));
        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
    }

    @Test
    void save_探测维度768_拒绝且message带维度() {
        when(modelMapper.selectById(6L)).thenReturn(usableEmbeddingModel());
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider());
        when(registry.getEmbeddingModel(6L)).thenReturn(embeddingModel);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[768]);

        BizException ex = assertThrows(BizException.class, () -> service.save(6L));

        assertEquals(ProviderError.EMBEDDING_DIMENSION_MISMATCH, ex.errorCode());
        assertTrue(ex.getMessage().contains("768"));
        verify(settingMapper, never()).insert(any(SystemSetting.class));
    }

    @Test
    void save_探测1024维_首次insert() {
        when(modelMapper.selectById(6L)).thenReturn(usableEmbeddingModel());
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider());
        when(registry.getEmbeddingModel(6L)).thenReturn(embeddingModel);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[1024]);
        when(settingMapper.selectOne(any())).thenReturn(null);
        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);

        service.save(6L);

        verify(settingMapper).insert(captor.capture());
        assertEquals("embedding_model_id", captor.getValue().getSettingKey());
        assertEquals("6", captor.getValue().getSettingValue());
    }

    @Test
    void save_已有设置_update覆盖() {
        when(modelMapper.selectById(6L)).thenReturn(usableEmbeddingModel());
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider());
        when(registry.getEmbeddingModel(6L)).thenReturn(embeddingModel);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[1024]);
        when(settingMapper.selectOne(any())).thenReturn(settingRow("3"));
        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);

        service.save(6L);

        verify(settingMapper).updateById(captor.capture());
        assertEquals("6", captor.getValue().getSettingValue());
        verify(settingMapper, never()).insert(any(SystemSetting.class));
    }

    @Test
    void currentModelId_未配置返回null_已配置返回Long() {
        when(settingMapper.selectOne(any())).thenReturn(null);
        assertNull(service.currentModelId());
        when(settingMapper.selectOne(any())).thenReturn(settingRow("6"));
        assertEquals(6L, service.currentModelId());
    }
}
