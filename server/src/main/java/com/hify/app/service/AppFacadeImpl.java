package com.hify.app.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.AppRuntimeView;
import com.hify.app.constant.AppStatus;
import com.hify.app.constant.AppType;
import com.hify.app.entity.App;
import com.hify.app.mapper.AppMapper;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * {@link AppFacade} 实现：纯读 + 投影到 AppRuntimeView（不 import entity 的禁令只约束 api/dto，service 可碰 entity）。
 */
@Service
public class AppFacadeImpl implements AppFacade {

    private final AppMapper appMapper;

    public AppFacadeImpl(AppMapper appMapper) {
        this.appMapper = appMapper;
    }

    @Override
    public Optional<AppRuntimeView> findRunnableChatApp(Long appId) {
        if (appId == null) {
            return Optional.empty();
        }
        App app = appMapper.selectById(appId);
        if (app == null
                || !AppType.CHAT.value().equals(app.getType())
                || !AppStatus.ENABLED.value().equals(app.getStatus())
                || app.getModelId() == null) {
            return Optional.empty();
        }
        String systemPrompt = app.getConfig() == null ? null : app.getConfig().systemPrompt();
        return Optional.of(new AppRuntimeView(app.getId(), app.getModelId(), systemPrompt));
    }
}
