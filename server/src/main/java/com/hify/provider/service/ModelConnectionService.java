package com.hify.provider.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.api.dto.ModelTestResponse;
import com.hify.provider.constant.ModelType;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.dto.ProviderTestResponse;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

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

    /**
     * 供应商级试连接：优先挑启用的 chat 模型（ping 便宜），无 chat 用 embedding；
     * 真实调用后成败都落库（最近一次测试三字段）；「无启用模型」没发请求，不落库。
     */
    public ProviderTestResponse testProvider(Long providerId) {
        ModelProvider provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw new BizException(CommonError.NOT_FOUND, "供应商不存在");
        }
        if (!ProviderStatus.ENABLED.value().equals(provider.getStatus())) {
            throw new BizException(ProviderError.MODEL_NOT_USABLE, "供应商已禁用，无法试连接");
        }
        List<AiModel> models = modelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, providerId)
                .eq(AiModel::getStatus, ProviderStatus.ENABLED.value()));
        AiModel candidate = models.stream()
                .filter(m -> ModelType.CHAT.value().equals(m.getType()))
                .findFirst()
                .orElseGet(() -> models.isEmpty() ? null : models.get(0));
        if (candidate == null) {
            throw new BizException(ProviderError.MODEL_NOT_USABLE, "该供应商下暂无启用的模型，无法试连接");
        }
        try {
            String sample = pingModel(candidate);
            saveResult(providerId, "ok", null);
            return new ProviderTestResponse(candidate.getName(), sample);
        } catch (RuntimeException e) {
            saveResult(providerId, "fail", e.getMessage());
            throw e;
        }
    }

    /** updateById 会忽略 null 字段，清空 last_test_error 必须用 UpdateWrapper 显式 set NULL。 */
    private void saveResult(Long providerId, String status, String error) {
        providerMapper.update(null, new LambdaUpdateWrapper<ModelProvider>()
                .eq(ModelProvider::getId, providerId)
                .set(ModelProvider::getLastTestStatus, status)
                .set(ModelProvider::getLastTestAt, OffsetDateTime.now())
                .set(ModelProvider::getLastTestError, error));
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
