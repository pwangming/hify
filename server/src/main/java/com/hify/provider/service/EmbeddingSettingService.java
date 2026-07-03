package com.hify.provider.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.constant.ModelType;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.dto.EmbeddingSettingResponse;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.entity.SystemSetting;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import com.hify.provider.mapper.SystemSettingMapper;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.springframework.stereotype.Service;

/**
 * 系统级 embedding 模型设置（system_setting 键 embedding_model_id）。
 * save 含一次真实外部调用，因此本类不加 @Transactional。
 */
@Service
public class EmbeddingSettingService {

    public static final String KEY_EMBEDDING_MODEL_ID = "embedding_model_id";
    private static final String PROBE_TEXT = "hify embedding probe";

    private final SystemSettingMapper settingMapper;
    private final AiModelMapper modelMapper;
    private final ModelProviderMapper providerMapper;
    private final ResilienceRegistry registry;

    public EmbeddingSettingService(SystemSettingMapper settingMapper, AiModelMapper modelMapper,
                                   ModelProviderMapper providerMapper, ResilienceRegistry registry) {
        this.settingMapper = settingMapper;
        this.modelMapper = modelMapper;
        this.providerMapper = providerMapper;
        this.registry = registry;
    }

    /** 回显当前设置；未配置时两字段均 null。模型名不管启停都回显。 */
    public EmbeddingSettingResponse get() {
        Long modelId = currentModelId();
        if (modelId == null) {
            return new EmbeddingSettingResponse(null, null);
        }
        AiModel model = modelMapper.selectById(modelId);
        return new EmbeddingSettingResponse(modelId, model == null ? null : model.getName());
    }

    /** 当前设置的 embedding 模型 id；未配置返回 null。 */
    public Long currentModelId() {
        SystemSetting s = selectRow();
        return (s == null || s.getSettingValue() == null) ? null : Long.valueOf(s.getSettingValue());
    }

    /** 校验可用性，事务外探测维度，再写 system_setting。 */
    public EmbeddingSettingResponse save(Long modelId) {
        AiModel model = modelMapper.selectById(modelId);
        if (model == null) {
            throw new BizException(CommonError.NOT_FOUND, "模型不存在");
        }
        if (!ModelType.EMBEDDING.value().equals(model.getType())
                || !ProviderStatus.ENABLED.value().equals(model.getStatus())) {
            throw new BizException(ProviderError.MODEL_NOT_USABLE);
        }
        ModelProvider provider = providerMapper.selectById(model.getProviderId());
        if (provider == null || !ProviderStatus.ENABLED.value().equals(provider.getStatus())) {
            throw new BizException(ProviderError.MODEL_NOT_USABLE);
        }
        float[] vector = registry.getEmbeddingModel(modelId).embed(PROBE_TEXT);
        if (vector.length != ChatClientFactory.EMBEDDING_DIMENSION) {
            throw new BizException(ProviderError.EMBEDDING_DIMENSION_MISMATCH,
                    "该模型输出 " + vector.length + " 维，需 " + ChatClientFactory.EMBEDDING_DIMENSION
                            + " 维，请换支持 1024 维输出的模型");
        }
        upsert(modelId);
        return get();
    }

    private SystemSetting selectRow() {
        return settingMapper.selectOne(new LambdaQueryWrapper<SystemSetting>()
                .eq(SystemSetting::getSettingKey, KEY_EMBEDDING_MODEL_ID));
    }

    private void upsert(Long modelId) {
        SystemSetting existing = selectRow();
        if (existing == null) {
            SystemSetting s = new SystemSetting();
            s.setSettingKey(KEY_EMBEDDING_MODEL_ID);
            s.setSettingValue(String.valueOf(modelId));
            settingMapper.insert(s);
        } else {
            existing.setSettingValue(String.valueOf(modelId));
            settingMapper.updateById(existing);
        }
    }
}
