package com.hify.workflow.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.WorkflowAppView;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.common.page.CursorResult;
import com.hify.infra.security.CurrentUser;
import com.hify.usage.api.UsageFacade;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.dto.NodeRunView;
import com.hify.workflow.dto.RunResponse;
import com.hify.workflow.dto.RunSummaryView;
import com.hify.workflow.entity.WorkflowDef;
import com.hify.workflow.entity.WorkflowNodeRun;
import com.hify.workflow.entity.WorkflowRun;
import com.hify.workflow.mapper.WorkflowRunMapper;
import com.hify.workflow.service.engine.EngineResult;
import com.hify.workflow.service.engine.GraphValidator;
import com.hify.workflow.service.engine.RunContext;
import com.hify.workflow.service.engine.WorkflowEngine;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 触发编排（spec §2 链路）。<b>本类禁止 @Transactional</b>——engine.execute 内有 LLM IO；
 * 落库全在 WorkflowRunStore 短事务。前置检查顺序（spec §3）：
 * app 存在(10005) → enabled(10006) → 草稿存在(10005) → 配额(14001) → 图校验(18001) → 必填入参(10001)。
 */
@Service
public class WorkflowRunService {

    private final AppFacade appFacade;
    private final UsageFacade usageFacade;
    private final WorkflowDraftService draftService;
    private final GraphValidator validator;
    private final WorkflowEngine engine;
    private final WorkflowRunStore store;
    private final WorkflowRunMapper runMapper;

    public WorkflowRunService(AppFacade appFacade, UsageFacade usageFacade,
                              WorkflowDraftService draftService, GraphValidator validator,
                              WorkflowEngine engine, WorkflowRunStore store, WorkflowRunMapper runMapper) {
        this.appFacade = appFacade;
        this.usageFacade = usageFacade;
        this.draftService = draftService;
        this.validator = validator;
        this.engine = engine;
        this.store = store;
        this.runMapper = runMapper;
    }

    /** 同步触发一次运行：跑完（或失败）后返回完整 run。运行失败不抛异常——failed 是合法终态（拍板 #7）。 */
    public RunResponse run(Long appId, Map<String, Object> inputs, CurrentUser user) {
        WorkflowAppView app = draftService.requireWorkflowApp(appId);
        if (!app.enabled()) {
            throw new BizException(CommonError.CONFLICT, "应用已停用，无法触发运行");
        }
        WorkflowDef def = draftService.requireDraft(appId);
        usageFacade.checkQuota(user.userId(), appId);
        List<GraphNode> ordered = validator.validateAndOrder(def.getGraph());
        checkRequiredInputs(ordered.get(0), inputs);   // 拓扑序首位必为 start（唯一入度 0 节点）

        WorkflowRun run = store.createRun(appId, def.getId(), user.userId(), inputs);
        long startAt = System.currentTimeMillis();
        EngineResult result;
        try {
            result = engine.execute(run.getId(), ordered, def.getGraph().edges(),
                    inputs, new RunContext(user.userId(), appId));
        } catch (RuntimeException e) {
            // 引擎内节点失败已收敛为 EngineResult；能抛到这里的是落库等非预期异常。
            // 兜底收尾 run 终态（僵尸自愈只在重启时跑，不兜这条会永久卡 running），再上抛走全局 500。
            store.markRunFailed(run.getId(), "系统异常，执行中断", System.currentTimeMillis() - startAt);
            throw e;
        }
        long elapsed = System.currentTimeMillis() - startAt;
        if (result.succeeded()) {
            store.markRunSucceeded(run.getId(), result.outputs(), elapsed);
        } else {
            store.markRunFailed(run.getId(), result.errorMessage(), elapsed);
        }
        return getRun(run.getId());
    }

    /** 运行详情 + 逐节点日志（全员可查，团队共享制）。 */
    public RunResponse getRun(Long runId) {
        WorkflowRun run = store.getRun(runId);
        if (run == null) {
            throw new BizException(CommonError.NOT_FOUND, "运行记录不存在");
        }
        List<NodeRunView> nodeRuns = store.listNodeRuns(runId).stream().map(this::toNodeView).toList();
        return new RunResponse(run.getId(), run.getStatus(), run.getInputs(), run.getOutputs(),
                run.getErrorMessage(), run.getElapsedMs(), run.getCreateTime(), nodeRuns);
    }

    /** 运行历史（游标分页，api-standards §3.1）。多查一条探测 hasMore。 */
    public CursorResult<RunSummaryView> listRuns(Long appId, String cursor, int limit) {
        if (limit < 1 || limit > 100) {
            throw new BizException(CommonError.PARAM_INVALID, "limit 必须在 1~100 之间");
        }
        draftService.requireWorkflowApp(appId);
        List<WorkflowRun> rows;
        if (StringUtils.hasText(cursor)) {
            RunCursor.Cursor c = RunCursor.decode(cursor);
            rows = runMapper.afterCursor(appId, c.createTime(), c.id(), limit + 1);
        } else {
            rows = runMapper.firstPage(appId, limit + 1);
        }
        boolean hasMore = rows.size() > limit;
        List<WorkflowRun> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (hasMore) {
            WorkflowRun last = page.get(page.size() - 1);
            nextCursor = RunCursor.encode(last.getCreateTime(), last.getId());
        }
        List<RunSummaryView> list = new ArrayList<>(page.stream().map(this::toSummary).toList());
        return CursorResult.of(list, nextCursor, hasMore);
    }

    /** start 节点声明的 required 输入必须齐全且非空白（10001，复用通用码）。 */
    private void checkRequiredInputs(GraphNode startNode, Map<String, Object> inputs) {
        Object declared = startNode.data() == null ? null : startNode.data().get("inputs");
        if (!(declared instanceof List<?> list)) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("required"))) {
                String name = String.valueOf(m.get("name"));
                Object value = inputs == null ? null : inputs.get(name);
                if (value == null || String.valueOf(value).isBlank()) {
                    missing.add(name);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new BizException(CommonError.PARAM_INVALID, "缺少必填输入：" + String.join("、", missing));
        }
    }

    private NodeRunView toNodeView(WorkflowNodeRun n) {
        return new NodeRunView(n.getId(), n.getNodeId(), n.getNodeType(), n.getStatus(),
                n.getInputs(), n.getOutputs(), n.getErrorMessage(), n.getElapsedMs(), n.getCreateTime());
    }

    private RunSummaryView toSummary(WorkflowRun r) {
        return new RunSummaryView(r.getId(), r.getStatus(), r.getErrorMessage(),
                r.getElapsedMs(), r.getCreateTime());
    }
}
