package com.hify.app.service;

import com.hify.app.api.dto.AppConfig;
import com.hify.app.constant.AppError;
import com.hify.app.constant.AppStatus;
import com.hify.app.constant.AppType;
import com.hify.app.dto.AppResponse;
import com.hify.app.dto.CreateAppRequest;
import com.hify.app.entity.App;
import com.hify.app.mapper.AppMapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应用业务逻辑。具体类 + @Service（不拆接口）。团队共享权限判定在本层（assertCanModify）。
 * 当前用户由 controller 经 CurrentUserHolder 传入，本层不直接读安全上下文（便于单测）。
 */
@Service
public class AppService {

    private final AppMapper appMapper;

    public AppService(AppMapper appMapper) {
        this.appMapper = appMapper;
    }

    @Transactional
    public AppResponse create(CreateAppRequest req, CurrentUser current) {
        if (!AppType.CHAT.value().equals(req.type())) {
            throw new BizException(AppError.APP_TYPE_NOT_SUPPORTED);
        }
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

    AppResponse toResponse(App e) {
        return new AppResponse(
                e.getId(), e.getName(), e.getDescription(), e.getType(),
                e.getModelId(), e.getConfig() == null ? new AppConfig(null) : e.getConfig(),
                e.getOwnerId(), e.getStatus(), e.getCreateTime(), e.getUpdateTime());
    }
}
