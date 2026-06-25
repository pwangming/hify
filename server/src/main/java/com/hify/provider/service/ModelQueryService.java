package com.hify.provider.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.provider.api.dto.ModelView;
import com.hify.provider.constant.ModelType;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模型的只读查询（@Service，无 @Transactional）。与 AiModelService（admin CRUD）职责分离。
 * 「可用」= 模型 enabled + type=chat + 所属供应商 enabled（spec §决策3）。
 *
 * <p>连带供应商状态的判定用两次 MyBatis-Plus wrapper 查询拼装，不写手写 SQL（沿用「手写 SQL 留到
 * knowledge 轮」约定）。一期数据量小，两查足够；真出现性能问题再换 join，不破坏对外契约。
 */
@Service
public class ModelQueryService {

    private final AiModelMapper modelMapper;
    private final ModelProviderMapper providerMapper;

    public ModelQueryService(AiModelMapper modelMapper, ModelProviderMapper providerMapper) {
        this.modelMapper = modelMapper;
        this.providerMapper = providerMapper;
    }

    /** 按 id 取一个「可用」的 chat 模型；不存在/停用/非 chat/供应商停用均返回 empty。 */
    public Optional<ModelView> findUsableChatModel(Long modelId) {
        AiModel m = modelMapper.selectById(modelId);
        if (m == null
                || !ProviderStatus.ENABLED.value().equals(m.getStatus())
                || !ModelType.CHAT.value().equals(m.getType())) {
            return Optional.empty();
        }
        ModelProvider p = providerMapper.selectById(m.getProviderId());
        if (p == null || !ProviderStatus.ENABLED.value().equals(p.getStatus())) {
            return Optional.empty();
        }
        return Optional.of(new ModelView(m.getId(), m.getName(), m.getType(), p.getName()));
    }

    /** 列全部「可用」模型，按模型名排序。type 为空兜底为 chat（本轮仅 chat 有意义）。 */
    public List<ModelView> listUsableChatModels(String type) {
        String t = StringUtils.hasText(type) ? type : ModelType.CHAT.value();
        // @TableLogic 自动加 where deleted=false
        List<AiModel> models = modelMapper.selectList(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getType, t)
                        .eq(AiModel::getStatus, ProviderStatus.ENABLED.value()));
        if (models.isEmpty()) {
            return List.of();
        }
        Set<Long> providerIds = models.stream().map(AiModel::getProviderId).collect(Collectors.toSet());
        Map<Long, String> enabledProviderName = providerMapper.selectBatchIds(providerIds).stream()
                .filter(p -> ProviderStatus.ENABLED.value().equals(p.getStatus()))
                .collect(Collectors.toMap(ModelProvider::getId, ModelProvider::getName));
        return models.stream()
                .filter(m -> enabledProviderName.containsKey(m.getProviderId()))
                .map(m -> new ModelView(m.getId(), m.getName(), m.getType(),
                        enabledProviderName.get(m.getProviderId())))
                .sorted(Comparator.comparing(ModelView::name, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
