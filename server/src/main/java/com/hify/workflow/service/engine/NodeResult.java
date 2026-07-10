package com.hify.workflow.service.engine;

import java.util.Map;

/**
 * 节点执行结果：inputs=变量替换后的实际输入（落 node_run.inputs 供排障），outputs=节点输出（进 RunContext）。
 * inputs 可为 null（如 end 节点无独立输入）。
 */
public record NodeResult(Map<String, Object> inputs, Map<String, Object> outputs) {
}
