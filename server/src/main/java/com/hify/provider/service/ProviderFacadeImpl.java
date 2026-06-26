package com.hify.provider.service;

import com.hify.provider.api.ProviderFacade;
import com.hify.provider.api.dto.ModelView;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.springframework.ai.chat.client.ChatClient;
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

    public ProviderFacadeImpl(ModelQueryService modelQueryService, ResilienceRegistry resilienceRegistry) {
        this.modelQueryService = modelQueryService;
        this.resilienceRegistry = resilienceRegistry;
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
    public Set<Long> filterUsableChatModelIds(Collection<Long> modelIds) {
        return modelQueryService.filterUsableChatModelIds(modelIds);
    }

    @Override
    public ChatClient getChatClient(Long modelId) {
        return resilienceRegistry.getChatClient(modelId);
    }
}
