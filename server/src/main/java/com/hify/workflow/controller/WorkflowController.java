package com.hify.workflow.controller;

import com.hify.common.Result;
import com.hify.common.page.CursorResult;
import com.hify.infra.security.CurrentUserHolder;
import com.hify.workflow.dto.DraftResponse;
import com.hify.workflow.dto.RunRequest;
import com.hify.workflow.dto.RunResponse;
import com.hify.workflow.dto.RunSummaryView;
import com.hify.workflow.dto.SaveDraftRequest;
import com.hify.workflow.service.WorkflowDraftService;
import com.hify.workflow.service.WorkflowRunService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * workflow 成员接口（/api/v1/workflow/**，api-standards 路由三族）。
 * draft 是单例子资源（每 app 一份草稿，spec 拍板 #6）：GET 读 / PUT 全量写。
 * 协议层：@Valid → 当前用户 → service → Result；无业务逻辑、无 try-catch、无 @Transactional。
 */
@RestController
@RequestMapping("/api/v1/workflow")
public class WorkflowController {

    private final WorkflowDraftService draftService;
    private final WorkflowRunService runService;

    public WorkflowController(WorkflowDraftService draftService, WorkflowRunService runService) {
        this.draftService = draftService;
        this.runService = runService;
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

    @PostMapping("/apps/{appId}/runs")
    public Result<RunResponse> run(@PathVariable Long appId, @RequestBody(required = false) RunRequest request) {
        Map<String, Object> inputs = request == null ? null : request.inputs();
        return Result.ok(runService.run(appId, inputs, CurrentUserHolder.current()));
    }

    @GetMapping("/apps/{appId}/runs")
    public Result<CursorResult<RunSummaryView>> listRuns(@PathVariable Long appId,
                                                         @RequestParam(required = false) String cursor,
                                                         @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(runService.listRuns(appId, cursor, limit));
    }

    @GetMapping("/runs/{id}")
    public Result<RunResponse> getRun(@PathVariable Long id) {
        return Result.ok(runService.getRun(id));
    }
}
