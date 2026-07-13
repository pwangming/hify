package com.hify.app.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hify.app.api.dto.AppConfig;
import com.hify.app.constant.AppError;
import com.hify.app.constant.AppStatus;
import com.hify.app.constant.AppType;
import com.hify.app.dto.AppResponse;
import com.hify.app.dto.CreateAppRequest;
import com.hify.app.dto.UpdateAppRequest;
import com.hify.app.entity.App;
import com.hify.app.entity.AppDatasetRel;
import com.hify.app.mapper.AppDatasetRelMapper;
import com.hify.app.mapper.AppMapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.common.page.PageResult;
import com.hify.infra.security.CurrentUser;
import com.hify.knowledge.api.KnowledgeFacade;
import com.hify.provider.api.ProviderFacade;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用业务逻辑。具体类 + @Service（不拆接口）。团队共享权限判定在本层（assertCanModify）。
 * 当前用户由 controller 经 CurrentUserHolder 传入，本层不直接读安全上下文（便于单测）。
 */
@Service
public class AppService {

    private final AppMapper appMapper;
    private final ProviderFacade providerFacade;
    private final AppDatasetRelMapper relMapper;
    private final KnowledgeFacade knowledgeFacade;

    public AppService(AppMapper appMapper, ProviderFacade providerFacade,
                      AppDatasetRelMapper relMapper, KnowledgeFacade knowledgeFacade) {
        this.appMapper = appMapper;
        this.providerFacade = providerFacade;
        this.relMapper = relMapper;
        this.knowledgeFacade = knowledgeFacade;
    }

    @Transactional
    public AppResponse create(CreateAppRequest req, CurrentUser current) {
        if (!AppType.CHAT.value().equals(req.type()) && !AppType.WORKFLOW.value().equals(req.type())) {
            throw new BizException(AppError.APP_TYPE_NOT_SUPPORTED);
        }
        assertModelUsableIfPresent(req.modelId());
        List<Long> datasetIds = req.datasetIds() == null ? List.of() : req.datasetIds();
        knowledgeFacade.validateDatasetIds(datasetIds);
        App entity = new App();
        entity.setName(req.name());
        entity.setDescription(req.description());
        entity.setType(req.type());
        entity.setModelId(req.modelId());
        entity.setConfig(req.config() == null ? new AppConfig(null, false) : req.config());
        entity.setOwnerId(current.userId());
        entity.setStatus(AppStatus.ENABLED.value());
        try {
            appMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "应用名已存在", e);
        }
        replaceDatasetBindings(entity.getId(), datasetIds);
        return toResponse(entity, modelNameOf(entity.getModelId()), modelUsableOf(entity.getModelId()),
                datasetIds.stream().distinct().toList());
    }

    public AppResponse get(Long id) {
        App app = appMapper.selectById(id);
        if (app == null) {
            throw new BizException(CommonError.NOT_FOUND, "应用不存在");
        }
        return toResponse(app, modelNameOf(app.getModelId()), modelUsableOf(app.getModelId()),
                datasetIdsOf(app.getId()));
    }

    public PageResult<AppResponse> page(String keyword, String type, int page, int size) {
        if (page < 1 || size < 1 || (long) page * size > 10_000) {
            throw new BizException(CommonError.PARAM_INVALID, "分页参数非法或过深，请用筛选条件缩小范围");
        }
        Page<App> result = appMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<App>()
                        .like(StringUtils.hasText(keyword), App::getName, keyword)
                        .eq(StringUtils.hasText(type), App::getType, type)
                        .orderByDesc(App::getId)); // 以 id 结尾保证稳定排序；@TableLogic 自动加 deleted=false
        List<App> records = result.getRecords();
        List<Long> modelIds = records.stream()
                .map(App::getModelId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> names = providerFacade.getModelNames(modelIds);
        Set<Long> usable = providerFacade.filterUsableChatModelIds(modelIds);
        Map<Long, List<Long>> bindings = datasetIdsByApp(records.stream().map(App::getId).toList());
        List<AppResponse> list = records.stream()
                .map(a -> toResponse(a,
                        a.getModelId() == null ? null : names.get(a.getModelId()),
                        a.getModelId() != null && usable.contains(a.getModelId()),
                        bindings.getOrDefault(a.getId(), List.of())))
                .toList();
        return PageResult.of(list, result.getTotal(), page, size);
    }

    @Transactional
    public AppResponse update(Long id, UpdateAppRequest req, CurrentUser current) {
        App app = loadOrThrow(id);
        assertCanModify(app, current);
        assertModelUsableIfPresent(req.modelId());
        List<Long> datasetIds = req.datasetIds() == null ? List.of() : req.datasetIds();
        if (AppType.CHAT.value().equals(app.getType())) {
            knowledgeFacade.validateDatasetIds(datasetIds);
        }
        app.setName(req.name());
        app.setDescription(req.description());
        app.setModelId(req.modelId());
        app.setConfig(req.config() == null ? new AppConfig(null, false) : req.config());
        try {
            appMapper.updateById(app);
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "应用名已存在", e);
        }
        if (AppType.CHAT.value().equals(app.getType())) {
            replaceDatasetBindings(app.getId(), datasetIds);
        }
        return toResponse(app, modelNameOf(app.getModelId()), modelUsableOf(app.getModelId()),
                datasetIdsOf(app.getId()));
    }

    @Transactional
    public void delete(Long id, CurrentUser current) {
        App app = appMapper.selectById(id);
        if (app == null) {
            return; // 幂等：删不存在的也算成功（api-standards 2.2）
        }
        assertCanModify(app, current);
        appMapper.deleteById(id);
    }

    @Transactional
    public void enable(Long id, CurrentUser current) {
        setStatus(id, current, AppStatus.ENABLED);
    }

    @Transactional
    public void disable(Long id, CurrentUser current) {
        setStatus(id, current, AppStatus.DISABLED);
    }

    private void setStatus(Long id, CurrentUser current, AppStatus status) {
        App app = loadOrThrow(id);
        assertCanModify(app, current);
        app.setStatus(status.value());
        appMapper.updateById(app);
    }

    private App loadOrThrow(Long id) {
        App app = appMapper.selectById(id);
        if (app == null) {
            throw new BizException(CommonError.NOT_FOUND, "应用不存在");
        }
        return app;
    }

    /** model_id 选填：非空时经 ProviderFacade 校验「可用」（enabled+chat+供应商enabled），不可用抛 16002。 */
    private void assertModelUsableIfPresent(Long modelId) {
        if (modelId != null && providerFacade.findUsableChatModel(modelId).isEmpty()) {
            throw new BizException(AppError.MODEL_NOT_USABLE);
        }
    }

    /** 团队共享制：仅 owner 或 Admin 可改/删/启停（api-standards 第 6 节），否则 FORBIDDEN。 */
    private void assertCanModify(App app, CurrentUser current) {
        if (!current.isAdmin() && !current.userId().equals(app.getOwnerId())) {
            throw new BizException(CommonError.FORBIDDEN, "仅创建者或管理员可操作该应用");
        }
    }

    /** 单个 modelId 的展示名（null 或解析不到则为 null）。 */
    private String modelNameOf(Long modelId) {
        if (modelId == null) {
            return null;
        }
        return providerFacade.getModelNames(List.of(modelId)).get(modelId);
    }

    /** 单个 modelId 当前是否「可用」（enabled+chat+供应商enabled）；null 模型为 false。 */
    private boolean modelUsableOf(Long modelId) {
        return modelId != null && providerFacade.findUsableChatModel(modelId).isPresent();
    }

    /** 全量替换绑定：软删该应用全部关系行 + 插入新勾选（去重）。调用方均在 @Transactional 内。 */
    private void replaceDatasetBindings(Long appId, List<Long> datasetIds) {
        relMapper.delete(new LambdaQueryWrapper<AppDatasetRel>().eq(AppDatasetRel::getAppId, appId));
        for (Long dsId : datasetIds.stream().distinct().toList()) {
            AppDatasetRel rel = new AppDatasetRel();
            rel.setAppId(appId);
            rel.setDatasetId(dsId);
            relMapper.insert(rel);
        }
    }

    /** 单应用的绑定知识库 ids（按绑定先后稳定排序）。 */
    private List<Long> datasetIdsOf(Long appId) {
        return relMapper.selectList(new LambdaQueryWrapper<AppDatasetRel>()
                        .eq(AppDatasetRel::getAppId, appId).orderByAsc(AppDatasetRel::getId))
                .stream().map(AppDatasetRel::getDatasetId).toList();
    }

    /** 批量取绑定（列表页防 N+1：一页一查）。 */
    private Map<Long, List<Long>> datasetIdsByApp(List<Long> appIds) {
        if (appIds.isEmpty()) {
            return Map.of();
        }
        return relMapper.selectList(new LambdaQueryWrapper<AppDatasetRel>()
                        .in(AppDatasetRel::getAppId, appIds).orderByAsc(AppDatasetRel::getId))
                .stream().collect(Collectors.groupingBy(AppDatasetRel::getAppId,
                        Collectors.mapping(AppDatasetRel::getDatasetId, Collectors.toList())));
    }

    AppResponse toResponse(App e, String modelName, boolean modelUsable, List<Long> datasetIds) {
        return new AppResponse(
                e.getId(), e.getName(), e.getDescription(), e.getType(),
                e.getModelId(), modelName, modelUsable,
                e.getConfig() == null ? new AppConfig(null, false) : e.getConfig(),
                datasetIds,
                e.getOwnerId(), e.getStatus(), e.getCreateTime(), e.getUpdateTime());
    }
}
