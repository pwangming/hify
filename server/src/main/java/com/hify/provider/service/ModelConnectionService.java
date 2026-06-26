package com.hify.provider.service;

import com.hify.provider.api.dto.ModelTestResponse;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.springframework.stereotype.Service;

/**
 * admin 测试供应商/模型连通：发一句最短 prompt，验证 Key/baseUrl/网络。
 * 不加 @Transactional——这是真实外部 IO（事务内禁外部调用，llm-resilience.md §1）。
 */
@Service
public class ModelConnectionService {

    private final ResilienceRegistry registry;

    public ModelConnectionService(ResilienceRegistry registry) {
        this.registry = registry;
    }

    public ModelTestResponse test(Long modelId) {
        String sample = registry.getChatClient(modelId)
                .prompt()
                .user("ping")
                .call()
                .content();
        return new ModelTestResponse(sample);
    }
}
