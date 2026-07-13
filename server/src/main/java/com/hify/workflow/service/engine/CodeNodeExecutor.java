package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.infra.outbound.SandboxClient;
import com.hify.infra.outbound.SandboxResult;
import com.hify.workflow.constant.NodeType;
import com.hify.workflow.constant.WorkflowError;
import com.hify.workflow.dto.GraphNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代码执行节点：渲染 inputs 映射（值均为字符串，见 spec 决策 3）→ 提交沙箱执行用户 main(**inputs)
 * → 沙箱返回的 dict 直接作节点输出，下游 {{codeId.key}} 引用。
 * 用户代码运行失败（沙箱 ok:false）＝节点失败（18002，文案取沙箱返回原因）；
 * 沙箱不可达/超时由 SandboxClient 抛 10008。变量引用缺失走 render 抛出（同其他节点）。
 * 渲染在 try 外：引用非法则原样抛 IllegalStateException（引擎按通用节点异常处理，同 HttpNodeExecutor）。
 */
@Component
public class CodeNodeExecutor implements NodeExecutor {

    private final SandboxClient sandbox;

    public CodeNodeExecutor(SandboxClient sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public String type() {
        return NodeType.CODE.value();
    }

    @Override
    public NodeResult execute(GraphNode node, RunContext ctx) {
        String code = String.valueOf(node.data().get("code"));   // validator 保证非空
        Object rawInputs = node.data().get("inputs");

        Map<String, String> args = new LinkedHashMap<>();
        if (rawInputs instanceof Map<?, ?> map) {                // validator 保证是 map（若存在）
            map.forEach((k, v) -> args.put(String.valueOf(k), ctx.render(String.valueOf(v))));
        }
        Map<String, Object> inputs = new LinkedHashMap<>(args);  // 快照落 node_run.inputs

        try {
            SandboxResult result = sandbox.run(code, args);
            if (!result.ok()) {
                throw new BizException(WorkflowError.CODE_EXECUTION_FAILED, result.error());
            }
            return new NodeResult(inputs, result.outputs());
        } catch (Exception e) {
            // 渲染已成功、执行才失败：渲染后的实参随异常带出，落 node_run.inputs 供排障（同 HTTP 节点模式）
            throw new NodeExecutionException(inputs, e);
        }
    }
}
