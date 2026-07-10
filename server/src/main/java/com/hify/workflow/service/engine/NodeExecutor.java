package com.hify.workflow.service.engine;

import com.hify.workflow.dto.GraphNode;

/**
 * 节点执行器：每类节点一个实现，按 type() 注册进 WorkflowEngine（唯一抽象点，spec §2）。
 * start/end 是引擎内建逻辑，不实现本接口。执行失败直接抛异常（引擎统一按节点失败处理，不重试）。
 */
public interface NodeExecutor {

    /** 对应 graph 里的 node.type（NodeType 枚举值）。 */
    String type();

    /** 执行节点：从 ctx 取上游输出做变量替换，返回（渲染后的输入快照, 本节点输出）。 */
    NodeResult execute(GraphNode node, RunContext ctx);
}
