package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.workflow.constant.NodeType;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.service.WorkflowRunStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 顺序执行器（spec 方案 A）：按拓扑序逐节点驱动，失败即停、不重试。
 * <b>本类禁止 @Transactional</b>——节点内发生真实 LLM IO；落库全走 store 的短事务。
 * start/end 为内建逻辑：start 把触发入参变成自己的输出；end 渲染 outputs 声明得到最终输出。
 */
@Component
public class WorkflowEngine {

    private final Map<String, NodeExecutor> executors;
    private final WorkflowRunStore store;

    public WorkflowEngine(List<NodeExecutor> executorList, WorkflowRunStore store) {
        this.executors = executorList.stream()
                .collect(Collectors.toUnmodifiableMap(NodeExecutor::type, e -> e));
        this.store = store;
    }

    public EngineResult execute(Long runId, List<GraphNode> ordered, List<GraphEdge> edges,
                                Map<String, Object> inputs, RunContext ctx) {
        Map<String, List<GraphEdge>> incoming = new HashMap<>();
        for (GraphEdge e : edges == null ? List.<GraphEdge>of() : edges) {
            incoming.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(e);
        }
        Set<String> executed = new HashSet<>();
        Map<String, Boolean> conditionResults = new HashMap<>();

        Map<String, Object> finalOutputs = Map.of();
        for (GraphNode node : ordered) {
            if (!alive(node, incoming, executed, conditionResults)) {
                store.createSkippedNodeRun(runId, node.id(), node.type());
                ctx.markSkipped(node.id());
                continue;
            }
            Long nodeRunId = store.createNodeRun(runId, node.id(), node.type());
            long startAt = System.currentTimeMillis();
            try {
                NodeResult result = executeNode(node, inputs, ctx);
                ctx.putOutput(node.id(), result.outputs());
                store.finishNodeRun(nodeRunId, true, result.inputs(), result.outputs(),
                        null, System.currentTimeMillis() - startAt);
                executed.add(node.id());
                if (NodeType.CONDITION.value().equals(node.type())) {
                    conditionResults.put(node.id(), (Boolean) result.outputs().get("result"));
                }
                if (NodeType.END.value().equals(node.type())) {
                    finalOutputs = result.outputs();
                }
            } catch (Exception e) {
                // NodeExecutionException 只是运载壳：inputs 取快照、失败文案看真实 cause
                Map<String, Object> failedInputs = e instanceof NodeExecutionException nee ? nee.inputs() : null;
                Throwable actual = e instanceof NodeExecutionException ? e.getCause() : e;
                String reason = actual instanceof BizException
                        ? actual.getMessage()
                        : "节点执行异常：" + actual.getMessage();
                store.finishNodeRun(nodeRunId, false, failedInputs, null,
                        reason, System.currentTimeMillis() - startAt);
                return EngineResult.failure(node.id(), "节点 " + node.id() + " 失败：" + reason);
            }
        }
        return EngineResult.success(finalOutputs);
    }

    /**
     * 活边判定（spec §3）：节点执行 ⇔ 存在一条活的入边。
     * 前驱是 condition ⇒ 只有 sourceHandle 与求值结果一致的边被选中；普通前驱 ⇒ 恒选中。
     * 拓扑序遍历保证判定时前驱状态已定；失败即停保证 executed 不含失败节点。
     */
    private boolean alive(GraphNode node, Map<String, List<GraphEdge>> incoming,
                          Set<String> executed, Map<String, Boolean> conditionResults) {
        if (NodeType.START.value().equals(node.type())) {
            return true;
        }
        for (GraphEdge e : incoming.getOrDefault(node.id(), List.of())) {
            if (!executed.contains(e.source())) {
                continue;   // 前驱被跳过：这条边必然不活
            }
            Boolean cond = conditionResults.get(e.source());
            if (cond == null || String.valueOf(cond).equals(e.sourceHandle())) {
                return true;
            }
        }
        return false;
    }

    private NodeResult executeNode(GraphNode node, Map<String, Object> inputs, RunContext ctx) {
        if (NodeType.START.value().equals(node.type())) {
            Map<String, Object> in = inputs == null ? Map.of() : inputs;
            return new NodeResult(in, in);
        }
        if (NodeType.END.value().equals(node.type())) {
            return new NodeResult(null, renderEndOutputs(node, ctx));
        }
        return executors.get(node.type()).execute(node, ctx);   // validator 已保证类型注册
    }

    /** end 节点：按 data.outputs 声明逐项渲染 {name, value 模板} → 最终输出 map。 */
    private Map<String, Object> renderEndOutputs(GraphNode node, RunContext ctx) {
        Object declared = node.data() == null ? null : node.data().get("outputs");
        Map<String, Object> out = new LinkedHashMap<>();
        if (declared instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    out.put(String.valueOf(m.get("name")), ctx.render(String.valueOf(m.get("value"))));
                }
            }
        }
        return out;
    }
}
