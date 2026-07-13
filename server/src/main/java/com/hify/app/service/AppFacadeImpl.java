package com.hify.app.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.app.api.AppFacade;
import com.hify.app.api.AppRuntimeView;
import com.hify.app.api.WorkflowAppView;
import com.hify.app.constant.AppStatus;
import com.hify.app.constant.AppType;
import com.hify.app.entity.App;
import com.hify.app.entity.AppDatasetRel;
import com.hify.app.mapper.AppDatasetRelMapper;
import com.hify.app.mapper.AppMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * {@link AppFacade} 实现：纯读 + 投影到 AppRuntimeView（不 import entity 的禁令只约束 api/dto，service 可碰 entity）。
 */
@Service
public class AppFacadeImpl implements AppFacade {

    private final AppMapper appMapper;
    private final AppDatasetRelMapper relMapper;

    public AppFacadeImpl(AppMapper appMapper, AppDatasetRelMapper relMapper) {
        this.appMapper = appMapper;
        this.relMapper = relMapper;
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
        boolean agentEnabled = app.getConfig() != null && app.getConfig().agentEnabled();
        List<Long> datasetIds = relMapper.selectList(new LambdaQueryWrapper<AppDatasetRel>()
                        .eq(AppDatasetRel::getAppId, app.getId()).orderByAsc(AppDatasetRel::getId))
                .stream().map(AppDatasetRel::getDatasetId).toList();
        return Optional.of(new AppRuntimeView(app.getId(), app.getModelId(), systemPrompt, datasetIds, agentEnabled));
    }

    @Override
    public Optional<WorkflowAppView> findWorkflowApp(Long appId) {
        if (appId == null) {
            return Optional.empty();
        }
        App app = appMapper.selectById(appId);
        if (app == null || !AppType.WORKFLOW.value().equals(app.getType())) {
            return Optional.empty();
        }
        return Optional.of(new WorkflowAppView(app.getId(), app.getOwnerId(),
                AppStatus.ENABLED.value().equals(app.getStatus())));
    }
}
