package com.hify.workflow.service.engine;

import com.hify.workflow.constant.NodeType;
import com.hify.workflow.dto.GraphNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 条件节点：渲染 left/right → ConditionEvaluator 求值 → 输出 {result}。
 * 引擎按 result 与出边 sourceHandle 匹配选路（spec §3）。求值失败＝节点失败（如数字比较遇非数字）。
 */
@Component
public class ConditionNodeExecutor implements NodeExecutor {

    @Override
    public String type() {
        return NodeType.CONDITION.value();
    }

    @Override
    public NodeResult execute(GraphNode node, RunContext ctx) {
        String left = ctx.render(String.valueOf(node.data().get("left")));
        String operator = String.valueOf(node.data().get("operator"));   // validator 已保证在白名单
        String right = ctx.render(String.valueOf(node.data().get("right")));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("left", left);
        inputs.put("operator", operator);
        inputs.put("right", right);

        try {
            boolean result = ConditionEvaluator.evaluate(left, operator, right);
            return new NodeResult(inputs, Map.of("result", result));
        } catch (Exception e) {
            // 渲染已成功、求值才失败：渲染后的实际比较值随异常落 node_run.inputs 供排障
            throw new NodeExecutionException(inputs, e);
        }
    }
}
