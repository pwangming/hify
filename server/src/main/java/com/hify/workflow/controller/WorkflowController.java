package com.hify.workflow.controller;

import com.hify.common.Result;
import com.hify.infra.security.CurrentUserHolder;
import com.hify.workflow.dto.DraftResponse;
import com.hify.workflow.dto.SaveDraftRequest;
import com.hify.workflow.service.WorkflowDraftService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * workflow 成员接口（/api/v1/workflow/**，api-standards 路由三族）。
 * draft 是单例子资源（每 app 一份草稿，spec 拍板 #6）：GET 读 / PUT 全量写。
 * 协议层：@Valid → 当前用户 → service → Result；无业务逻辑、无 try-catch、无 @Transactional。
 */
@RestController
@RequestMapping("/api/v1/workflow")
public class WorkflowController {

    private final WorkflowDraftService draftService;

    public WorkflowController(WorkflowDraftService draftService) {
        this.draftService = draftService;
    }

    @GetMapping("/apps/{appId}/draft")
    public Result<DraftResponse> getDraft(@PathVariable Long appId) {
        return Result.ok(draftService.getDraft(appId));
    }

    @PutMapping("/apps/{appId}/draft")
    public Result<DraftResponse> saveDraft(@PathVariable Long appId,
                                           @Valid @RequestBody SaveDraftRequest request) {
        return Result.ok(draftService.saveDraft(appId, request.graph(), CurrentUserHolder.current()));
    }
}
