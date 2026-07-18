package com.hify.provider.service;

import com.hify.common.exception.BizException;
import com.hify.provider.api.ModelPrice;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.api.ProviderFacade;
import com.hify.provider.api.dto.ModelView;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ProviderFacade} 实现：薄委托给内部 service / Registry，不写业务分支、不注入 Mapper
 * （code-organization：跨模块聚合写 service，Facade 只转发）。
 */
@Service
public class ProviderFacadeImpl implements ProviderFacade {

    private final ModelQueryService modelQueryService;
    private final ResilienceRegistry resilienceRegistry;
    private final EmbeddingSettingService embeddingSettingService;

    public ProviderFacadeImpl(ModelQueryService modelQueryService, ResilienceRegistry resilienceRegistry,
                              EmbeddingSettingService embeddingSettingService) {
        this.modelQueryService = modelQueryService;
        this.resilienceRegistry = resilienceRegistry;
        this.embeddingSettingService = embeddingSettingService;
    }

    @Override
    public Optional<ModelView> findUsableChatModel(Long modelId) {
        return modelQueryService.findUsableChatModel(modelId);
    }

    @Override
    public Map<Long, String> getModelNames(Collection<Long> modelIds) {
        return modelQueryService.getModelNames(modelIds);
    }

    @Override
    public Map<Long, ModelPrice> getModelPrices(Collection<Long> modelIds) {
        return modelQueryService.getModelPrices(modelIds);
    }

    @Override
    public Set<Long> filterUsableChatModelIds(Collection<Long> modelIds) {
        return modelQueryService.filterUsableChatModelIds(modelIds);
    }

    @Override
    public ChatClient getChatClient(Long modelId) {
        return resilienceRegistry.getChatClient(modelId);
    }

    @Override
    public EmbeddingModel getEmbeddingModel() {
        Long modelId = embeddingSettingService.currentModelId();
        if (modelId == null) {
            throw new BizException(ProviderError.EMBEDDING_MODEL_NOT_CONFIGURED);
        }
        return resilienceRegistry.getEmbeddingModel(modelId);
    }
}
