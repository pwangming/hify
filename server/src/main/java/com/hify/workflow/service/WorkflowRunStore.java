package com.hify.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.workflow.constant.RunStatus;
import com.hify.workflow.entity.WorkflowNodeRun;
import com.hify.workflow.entity.WorkflowRun;
import com.hify.workflow.mapper.WorkflowNodeRunMapper;
import com.hify.workflow.mapper.WorkflowRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * run/node_run 的落库出入口（仿 conversation 的 ConversationStore）：每个写方法一个独立短事务。
 * 引擎与服务层<b>不得</b>自带 @Transactional——LLM IO 必须发生在这些短事务之间（红线，spec §4）。
 */
@Service
public class WorkflowRunStore {

    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;

    public WorkflowRunStore(WorkflowRunMapper runMapper, WorkflowNodeRunMapper nodeRunMapper) {
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
    }

    @Transactional
    public WorkflowRun createRun(Long appId, Long defId, Long userId, Map<String, Object> inputs) {
        WorkflowRun run = new WorkflowRun();
        run.setAppId(appId);
        run.setDefId(defId);
        run.setUserId(userId);
        run.setStatus(RunStatus.RUNNING.value());
        run.setInputs(inputs);
        runMapper.insert(run);
        return run;
    }

    @Transactional
    public void markRunSucceeded(Long runId, Map<String, Object> outputs, long elapsedMs) {
        WorkflowRun patch = new WorkflowRun();
        patch.setId(runId);
        patch.setStatus(RunStatus.SUCCEEDED.value());
        patch.setOutputs(outputs);
        patch.setElapsedMs(elapsedMs);
        runMapper.updateById(patch);
    }

    @Transactional
    public void markRunFailed(Long runId, String errorMessage, long elapsedMs) {
        WorkflowRun patch = new WorkflowRun();
        patch.setId(runId);
        patch.setStatus(RunStatus.FAILED.value());
        patch.setErrorMessage(errorMessage);
        patch.setElapsedMs(elapsedMs);
        runMapper.updateById(patch);
    }

    @Transactional
    public Long createNodeRun(Long runId, String nodeId, String nodeType) {
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setRunId(runId);
        nodeRun.setNodeId(nodeId);
        nodeRun.setNodeType(nodeType);
        nodeRun.setStatus(RunStatus.RUNNING.value());
        nodeRunMapper.insert(nodeRun);
        return nodeRun.getId();
    }

    @Transactional
    public void finishNodeRun(Long nodeRunId, boolean succeeded, Map<String, Object> inputs,
                              Map<String, Object> outputs, String errorMessage, long elapsedMs) {
        WorkflowNodeRun patch = new WorkflowNodeRun();
        patch.setId(nodeRunId);
        patch.setStatus(succeeded ? RunStatus.SUCCEEDED.value() : RunStatus.FAILED.value());
        patch.setInputs(inputs);
        patch.setOutputs(outputs);
        patch.setErrorMessage(errorMessage);
        patch.setElapsedMs(elapsedMs);
        nodeRunMapper.updateById(patch);
    }

    /** 未选中分支上的节点：直接落终态 skipped（spec §3），无输入输出、耗时 0。 */
    @Transactional
    public void createSkippedNodeRun(Long runId, String nodeId, String nodeType) {
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setRunId(runId);
        nodeRun.setNodeId(nodeId);
        nodeRun.setNodeType(nodeType);
        nodeRun.setStatus(RunStatus.SKIPPED.value());
        nodeRun.setElapsedMs(0L);
        nodeRunMapper.insert(nodeRun);
    }

    /** 启动自愈：遗留 running 全部置 failed。返回重置总条数（run + node_run）。 */
    @Transactional
    public int resetZombies() {
        return runMapper.resetZombieRuns() + nodeRunMapper.resetZombieNodeRuns();
    }

    public WorkflowRun getRun(Long runId) {
        return runMapper.selectById(runId);
    }

    /** 一次 run 的节点日志，按执行顺序（id 升序）。 */
    public List<WorkflowNodeRun> listNodeRuns(Long runId) {
        return nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getRunId, runId).orderByAsc(WorkflowNodeRun::getId));
    }
}
