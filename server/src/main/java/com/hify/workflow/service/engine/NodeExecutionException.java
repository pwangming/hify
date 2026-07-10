package com.hify.workflow.service.engine;

import java.util.Map;

/**
 * 节点执行失败时携带已渲染的输入快照（引擎落 node_run.inputs 供排障：能看到实际发出去的提示词）。
 * cause 是真实失败原因；失败文案由引擎按 cause 生成。
 */
public class NodeExecutionException extends RuntimeException {

    private final transient Map<String, Object> inputs;

    public NodeExecutionException(Map<String, Object> inputs, Throwable cause) {
        super(cause.getMessage(), cause);
        this.inputs = inputs;
    }

    public Map<String, Object> inputs() {
        return inputs;
    }
}
