package com.hify.provider.service;

import com.hify.provider.api.ProviderFacade;
import com.hify.provider.api.dto.ModelView;
import org.springframework.stereotype.Service;

import java.util.Optional;

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
}
