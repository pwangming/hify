package com.hify.provider.service;

import com.hify.provider.api.ProviderFacade;
import com.hify.provider.api.dto.ModelView;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ProviderFacade} 实现：薄委托给内部只读 service，不写业务分支、不注入 Mapper
 * （code-organization：跨模块聚合写 service，Facade 只转发）。
 */
@Service
public class ProviderFacadeImpl implements ProviderFacade {

    private final ModelQueryService modelQueryService;

    public ProviderFacadeImpl(ModelQueryService modelQueryService) {
        this.modelQueryService = modelQueryService;
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
}
