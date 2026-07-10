package com.hify.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.app.api.AppFacade;
import com.hify.app.api.WorkflowAppView;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.security.CurrentUser;
import com.hify.workflow.dto.DraftResponse;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.entity.WorkflowDef;
import com.hify.workflow.mapper.WorkflowDefMapper;
import com.hify.workflow.service.engine.GraphValidator;
import org.springframework.stereotype.Service;

/**
 * 草稿定义读写。W1 每 app 恒一条 version=1（spec 拍板 #3）；保存前过 GraphValidator，
 * 不合法的图拒绝入库。权限：读全员，写 owner 或 Admin（api-standards §6）。
 */
@Service
public class WorkflowDraftService {

    private final WorkflowDefMapper defMapper;
    private final AppFacade appFacade;
    private final GraphValidator validator;

    public WorkflowDraftService(WorkflowDefMapper defMapper, AppFacade appFacade, GraphValidator validator) {
        this.defMapper = defMapper;
        this.appFacade = appFacade;
        this.validator = validator;
    }

    /** 读草稿；应用必须存在且为 workflow 型。无草稿返回 null（api-standards §4：对象字段 null=无）。 */
    public DraftResponse getDraft(Long appId) {
        requireWorkflowApp(appId);
        WorkflowDef def = findDef(appId);
        return def == null ? null : new DraftResponse(def.getGraph(), def.getUpdateTime());
    }

    /** 全量保存草稿（UPSERT，无先查后插竞态）。校验是纯内存计算，UPSERT 是单条短 SQL，无需事务。 */
    public DraftResponse saveDraft(Long appId, GraphDef graph, CurrentUser user) {
        WorkflowAppView app = requireWorkflowApp(appId);
        if (!user.isAdmin() && !user.userId().equals(app.ownerId())) {
            throw new BizException(CommonError.FORBIDDEN, "仅创建者或管理员可编辑工作流");
        }
        validator.validateAndOrder(graph);
        defMapper.upsertDraft(appId, graph);
        WorkflowDef saved = findDef(appId);
        return new DraftResponse(saved.getGraph(), saved.getUpdateTime());
    }

    /** 触发运行前取草稿：无草稿=还没配置工作流，10005。 */
    public WorkflowDef requireDraft(Long appId) {
        WorkflowDef def = findDef(appId);
        if (def == null) {
            throw new BizException(CommonError.NOT_FOUND, "工作流尚未配置，请先保存草稿");
        }
        return def;
    }

    /** 应用存在性守卫（包内共享给 WorkflowRunService）。 */
    WorkflowAppView requireWorkflowApp(Long appId) {
        return appFacade.findWorkflowApp(appId)
                .orElseThrow(() -> new BizException(CommonError.NOT_FOUND, "应用不存在或不是工作流应用"));
    }

    private WorkflowDef findDef(Long appId) {
        return defMapper.selectOne(new LambdaQueryWrapper<WorkflowDef>()
                .eq(WorkflowDef::getAppId, appId)
                .eq(WorkflowDef::getVersion, 1));
    }
}
