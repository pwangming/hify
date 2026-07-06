package com.hify.provider.service;

import com.hify.common.exception.BizException;
import com.hify.provider.api.dto.ModelTestResponse;
import com.hify.provider.constant.ModelType;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.entity.AiModel;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.springframework.stereotype.Service;

/**
 * admin 测试供应商/模型连通：chat 发一句最短 prompt，embedding 把它转向量，验证 Key/baseUrl/网络。
 * 不加 @Transactional——这是真实外部 IO（事务内禁外部调用，llm-resilience.md §1）。
 */
@Service
public class ModelConnectionService {

    private final ResilienceRegistry registry;
    private final AiModelMapper modelMapper;
    private final ModelProviderMapper providerMapper;

    public ModelConnectionService(ResilienceRegistry registry, AiModelMapper modelMapper,
                                  ModelProviderMapper providerMapper) {
        this.registry = registry;
        this.modelMapper = modelMapper;
        this.providerMapper = providerMapper;
    }

    /** 模型级测试：不回写供应商状态（modelKey 配错不代表供应商连接坏了）。 */
    public ModelTestResponse test(Long modelId) {
        AiModel model = modelMapper.selectById(modelId);
        if (model == null) {
            throw new BizException(ProviderError.MODEL_NOT_USABLE);
        }
        return new ModelTestResponse(pingModel(model));
    }

    /** 按模型类型真实调用一次；启用/类型校验由 registry 内部完成。 */
    private String pingModel(AiModel model) {
        if (ModelType.EMBEDDING.value().equals(model.getType())) {
            float[] vector = registry.getEmbeddingModel(model.getId()).embed("ping");
            return "已返回 " + vector.length + " 维向量";
        }
        return registry.getChatClient(model.getId())
                .prompt().user("ping").call().content();
    }
}
