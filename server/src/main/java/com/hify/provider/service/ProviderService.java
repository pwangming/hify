package com.hify.provider.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.dto.CreateProviderRequest;
import com.hify.provider.dto.ProviderResponse;
import com.hify.provider.dto.UpdateProviderRequest;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 模型供应商业务逻辑（具体类 + @Service，不拆接口——code-organization.md 第 2 节）。
 * 注入本模块 ModelProviderMapper 与 ApiKeyCipher；@Transactional 只在本层写方法；
 * 失败一律抛 BizException 交全局处理器转信封。Entity ↔ DTO 转换在本层（dto 包禁依赖 entity）。
 */
@Service
public class ProviderService {

    private final ModelProviderMapper providerMapper;
    private final AiModelMapper aiModelMapper;
    private final ApiKeyCipher apiKeyCipher;
    private final ResilienceRegistry resilienceRegistry;

    public ProviderService(ModelProviderMapper providerMapper, AiModelMapper aiModelMapper,
                           ApiKeyCipher apiKeyCipher, ResilienceRegistry resilienceRegistry) {
        this.providerMapper = providerMapper;
        this.aiModelMapper = aiModelMapper;
        this.apiKeyCipher = apiKeyCipher;
        this.resilienceRegistry = resilienceRegistry;
    }

    @Transactional
    public ProviderResponse create(CreateProviderRequest request) {
        assertNameAvailable(request.name(), null);
        ModelProvider entity = new ModelProvider();
        entity.setName(request.name());
        entity.setProtocol(request.protocol());
        entity.setBaseUrl(request.baseUrl());
        entity.setApiKeyCipher(apiKeyCipher.encrypt(request.apiKey()));
        entity.setApiKeyTail(tailOf(request.apiKey()));
        entity.setStatus(ProviderStatus.ENABLED.value());
        try {
            providerMapper.insert(entity); // id 回填；create_time/update_time/deleted 自动填充
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "供应商名称已存在", e);
        }
        return toResponse(entity);
    }

    @Transactional
    public ProviderResponse update(Long id, UpdateProviderRequest request) {
        ModelProvider entity = require(id);
        assertNameAvailable(request.name(), id);
        entity.setName(request.name());
        entity.setProtocol(request.protocol());
        entity.setBaseUrl(request.baseUrl());
        // 只写密钥例外：留空保留原密文/tail；非空才重新加密并刷新 tail
        if (StringUtils.hasText(request.apiKey())) {
            entity.setApiKeyCipher(apiKeyCipher.encrypt(request.apiKey()));
            entity.setApiKeyTail(tailOf(request.apiKey()));
        }
        try {
            providerMapper.updateById(entity); // update_time 自动刷新
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "供应商名称已存在", e);
        }
        resilienceRegistry.invalidate(id); // 配置/Key 变更热生效：清四件套 + 名下 client
        return toResponse(entity);
    }

    /** 列表：@TableLogic 自动加 where deleted=false，软删的不出现。按创建时间倒序。 */
    public List<ProviderResponse> list() {
        List<ModelProvider> rows = providerMapper.selectList(
                new LambdaQueryWrapper<ModelProvider>().orderByDesc(ModelProvider::getCreateTime));
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional
    public void enable(Long id) {
        ModelProvider entity = require(id);
        if (ProviderStatus.ENABLED.value().equals(entity.getStatus())) {
            return; // 幂等：已启用不写库
        }
        entity.setStatus(ProviderStatus.ENABLED.value());
        providerMapper.updateById(entity);
        resilienceRegistry.invalidate(id);
    }

    @Transactional
    public void disable(Long id) {
        ModelProvider entity = require(id);
        if (ProviderStatus.DISABLED.value().equals(entity.getStatus())) {
            return; // 幂等：已禁用不写库
        }
        entity.setStatus(ProviderStatus.DISABLED.value());
        providerMapper.updateById(entity);
        resilienceRegistry.invalidate(id);
    }

    /**
     * 逻辑删除供应商。收紧（B 轮）：其下尚有未删模型时拒绝，防悬空引用（CONFLICT）。
     * 无模型时走 @TableLogic 软删；删不存在的也算成功（幂等）。
     */
    @Transactional
    public void delete(Long id) {
        long models = aiModelMapper.selectCount(
                new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId, id));
        if (models > 0) {
            throw new BizException(CommonError.CONFLICT, "请先删除该供应商下的模型");
        }
        providerMapper.deleteById(id);
        resilienceRegistry.invalidate(id);
    }

    private ModelProvider require(Long id) {
        ModelProvider entity = providerMapper.selectById(id);
        if (entity == null) {
            throw new BizException(CommonError.NOT_FOUND, "供应商不存在");
        }
        return entity;
    }

    /** 名称在未软删范围内唯一；excludeId 非 null 时排除自身（更新场景）。 */
    private void assertNameAvailable(String name, Long excludeId) {
        LambdaQueryWrapper<ModelProvider> q = new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getName, name);
        if (excludeId != null) {
            q.ne(ModelProvider::getId, excludeId);
        }
        if (providerMapper.selectCount(q) > 0) {
            throw new BizException(CommonError.CONFLICT, "供应商名称已存在");
        }
    }

    /** 取明文后 4 位作掩码尾巴；不足 4 位整体返回。 */
    private String tailOf(String apiKey) {
        return apiKey.length() <= 4 ? apiKey : apiKey.substring(apiKey.length() - 4);
    }

    /** 实体→视图投影：api_key_cipher / 明文 key 绝不进 DTO。放 service 层（dto 禁依赖 entity）。 */
    private ProviderResponse toResponse(ModelProvider e) {
        return new ProviderResponse(
                e.getId(), e.getName(), e.getProtocol(), e.getBaseUrl(),
                e.getStatus(), e.getApiKeyTail(), e.getCreateTime());
    }
}
