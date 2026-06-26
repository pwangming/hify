package com.hify.provider.service.resilience;

import com.hify.common.exception.BizException;
import com.hify.provider.constant.ModelType;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import com.hify.provider.service.ChatClientFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 按 providerId 缓存韧性四件套、按 modelId 缓存 ChatClient；模块内 invalidate 热生效（admin 改配置即重建）。
 * getChatClient 自身只查库 + 建对象 + decrypt（纯 CPU），不发外部请求。
 */
@Component
public class ResilienceRegistry {

    private final AiModelMapper modelMapper;
    private final ModelProviderMapper providerMapper;
    private final ChatClientFactory factory;
    private final ExecutorService executor;

    private final Map<Long, ResilienceBundle> bundles = new ConcurrentHashMap<>();
    private final Map<Long, CachedClient> clients = new ConcurrentHashMap<>();

    private record CachedClient(Long providerId, ChatClient client) {}

    public ResilienceRegistry(AiModelMapper modelMapper, ModelProviderMapper providerMapper,
                              ChatClientFactory factory,
                              @Qualifier("llmCallExecutor") ExecutorService executor) {
        this.modelMapper = modelMapper;
        this.providerMapper = providerMapper;
        this.factory = factory;
        this.executor = executor;
    }

    /** 取一个「可用」chat 模型的 ChatClient（命中缓存直接返回）；不可用抛 12002。 */
    public ChatClient getChatClient(Long modelId) {
        CachedClient cached = clients.get(modelId);
        if (cached != null) {
            return cached.client();
        }
        AiModel model = modelMapper.selectById(modelId);
        if (model == null || !ModelType.CHAT.value().equals(model.getType())
                || !ProviderStatus.ENABLED.value().equals(model.getStatus())) {
            throw new BizException(ProviderError.MODEL_NOT_USABLE);
        }
        ModelProvider provider = providerMapper.selectById(model.getProviderId());
        if (provider == null || !ProviderStatus.ENABLED.value().equals(provider.getStatus())) {
            throw new BizException(ProviderError.MODEL_NOT_USABLE);
        }
        ResilienceBundle bundle = bundles.computeIfAbsent(provider.getId(),
                k -> ResilienceBundle.build(provider));
        ChatModel raw = factory.buildChatModel(provider, model);
        ChatClient client = ChatClient.create(new ResilientChatModel(raw, bundle, executor));
        clients.put(modelId, new CachedClient(provider.getId(), client));
        return client;
    }

    /** 供应商配置/Key/启停变更：清四件套 + 名下所有 client。 */
    public void invalidate(Long providerId) {
        bundles.remove(providerId);
        clients.values().removeIf(c -> c.providerId().equals(providerId));
    }

    /** 单个模型变更：只清该 client（供应商级四件套不动）。 */
    public void invalidateModel(Long modelId) {
        clients.remove(modelId);
    }
}
