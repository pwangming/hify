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
import com.hify.app.mapper.AppMapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.common.page.PageResult;
import com.hify.infra.security.CurrentUser;
import com.hify.provider.api.ProviderFacade;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 应用业务逻辑。具体类 + @Service（不拆接口）。团队共享权限判定在本层（assertCanModify）。
 * 当前用户由 controller 经 CurrentUserHolder 传入，本层不直接读安全上下文（便于单测）。
 */
@Service
public class AppService {

    private final AppMapper appMapper;
    private final ProviderFacade providerFacade;

    public AppService(AppMapper appMapper, ProviderFacade providerFacade) {
        this.appMapper = appMapper;
        this.providerFacade = providerFacade;
    }

    @Transactional
    public AppResponse create(CreateAppRequest req, CurrentUser current) {
        if (!AppType.CHAT.value().equals(req.type())) {
            throw new BizException(AppError.APP_TYPE_NOT_SUPPORTED);
        }
        assertModelUsableIfPresent(req.modelId());
        App entity = new App();
        entity.setName(req.name());
        entity.setDescription(req.description());
        entity.setType(req.type());
        entity.setModelId(req.modelId());
        entity.setConfig(req.config() == null ? new AppConfig(null) : req.config());
        entity.setOwnerId(current.userId());
        entity.setStatus(AppStatus.ENABLED.value());
        try {
            appMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "应用名已存在", e);
        }
        return toResponse(entity);
    }

    public AppResponse get(Long id) {
        App app = appMapper.selectById(id);
        if (app == null) {
            throw new BizException(CommonError.NOT_FOUND, "应用不存在");
        }
        return toResponse(app);
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
        List<AppResponse> list = result.getRecords().stream().map(this::toResponse).toList();
        return PageResult.of(list, result.getTotal(), page, size);
    }

    @Transactional
    public AppResponse update(Long id, UpdateAppRequest req, CurrentUser current) {
        App app = loadOrThrow(id);
        assertCanModify(app, current);
        assertModelUsableIfPresent(req.modelId());
        app.setName(req.name());
        app.setDescription(req.description());
        app.setModelId(req.modelId());
        app.setConfig(req.config() == null ? new AppConfig(null) : req.config());
        try {
            appMapper.updateById(app);
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "应用名已存在", e);
        }
        return toResponse(app);
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

    AppResponse toResponse(App e) {
        return new AppResponse(
                e.getId(), e.getName(), e.getDescription(), e.getType(),
                e.getModelId(), e.getConfig() == null ? new AppConfig(null) : e.getConfig(),
                e.getOwnerId(), e.getStatus(), e.getCreateTime(), e.getUpdateTime());
    }
}
