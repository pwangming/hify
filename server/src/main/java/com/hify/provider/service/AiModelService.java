package com.hify.provider.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.constant.ModelType;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.dto.CreateModelRequest;
import com.hify.provider.dto.ModelResponse;
import com.hify.provider.dto.UpdateModelRequest;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 具体模型业务逻辑（具体类 + @Service）。注入本模块 AiModelMapper 与 ModelProviderMapper
 * （读 provider 的 protocol 做 embedding 守卫，同模块内调用合规）。@Transactional 只在写方法。
 */
@Service
public class AiModelService {

    private static final String PROTOCOL_ANTHROPIC = "anthropic";

    private final AiModelMapper modelMapper;
    private final ModelProviderMapper providerMapper;
    private final ResilienceRegistry resilienceRegistry;

    public AiModelService(AiModelMapper modelMapper, ModelProviderMapper providerMapper,
                          ResilienceRegistry resilienceRegistry) {
        this.modelMapper = modelMapper;
        this.providerMapper = providerMapper;
        this.resilienceRegistry = resilienceRegistry;
    }

    @Transactional
    public ModelResponse create(Long providerId, CreateModelRequest request) {
        ModelProvider provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw new BizException(CommonError.NOT_FOUND, "供应商不存在");
        }
        // 协议守卫：Anthropic 无 embedding 能力
        if (PROTOCOL_ANTHROPIC.equals(provider.getProtocol())
                && ModelType.EMBEDDING.value().equals(request.type())) {
            throw new BizException(ProviderError.EMBEDDING_NOT_SUPPORTED);
        }
        assertKeyAvailable(providerId, request.modelKey(), null);
        AiModel entity = new AiModel();
        entity.setProviderId(providerId);
        entity.setType(request.type());
        entity.setName(request.name());
        entity.setModelKey(request.modelKey());
        entity.setStatus(ProviderStatus.ENABLED.value());
        try {
            modelMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "该供应商下已存在同名模型标识", e);
        }
        return toResponse(entity);
    }

    @Transactional
    public ModelResponse update(Long id, UpdateModelRequest request) {
        AiModel entity = require(id);
        assertKeyAvailable(entity.getProviderId(), request.modelKey(), id);
        entity.setName(request.name());
        entity.setModelKey(request.modelKey());
        try {
            modelMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "该供应商下已存在同名模型标识", e);
        }
        resilienceRegistry.invalidateModel(id); // 模型变更热生效：清该模型的 client 缓存
        return toResponse(entity);
    }

    /** 列某供应商下的模型（@TableLogic 自动加 where deleted=false），按创建时间倒序。 */
    public List<ModelResponse> listByProvider(Long providerId) {
        List<AiModel> rows = modelMapper.selectList(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getProviderId, providerId)
                        .orderByDesc(AiModel::getCreateTime));
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional
    public void enable(Long id) {
        AiModel entity = require(id);
        if (ProviderStatus.ENABLED.value().equals(entity.getStatus())) {
            return; // 幂等
        }
        entity.setStatus(ProviderStatus.ENABLED.value());
        modelMapper.updateById(entity);
        resilienceRegistry.invalidateModel(id);
    }

    @Transactional
    public void disable(Long id) {
        AiModel entity = require(id);
        if (ProviderStatus.DISABLED.value().equals(entity.getStatus())) {
            return; // 幂等
        }
        entity.setStatus(ProviderStatus.DISABLED.value());
        modelMapper.updateById(entity);
        resilienceRegistry.invalidateModel(id);
    }

    /** 逻辑删除：删不存在的也算成功（幂等）。 */
    @Transactional
    public void delete(Long id) {
        modelMapper.deleteById(id);
        resilienceRegistry.invalidateModel(id);
    }

    private AiModel require(Long id) {
        AiModel entity = modelMapper.selectById(id);
        if (entity == null) {
            throw new BizException(CommonError.NOT_FOUND, "模型不存在");
        }
        return entity;
    }

    /** 同一供应商下 model_key 唯一；excludeId 非 null 时排除自身（更新场景）。 */
    private void assertKeyAvailable(Long providerId, String modelKey, Long excludeId) {
        LambdaQueryWrapper<AiModel> q = new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, providerId)
                .eq(AiModel::getModelKey, modelKey);
        if (excludeId != null) {
            q.ne(AiModel::getId, excludeId);
        }
        if (modelMapper.selectCount(q) > 0) {
            throw new BizException(CommonError.CONFLICT, "该供应商下已存在同名模型标识");
        }
    }

    private ModelResponse toResponse(AiModel m) {
        return new ModelResponse(
                m.getId(), m.getProviderId(), m.getType(), m.getName(),
                m.getModelKey(), m.getStatus(), m.getCreateTime());
    }
}
