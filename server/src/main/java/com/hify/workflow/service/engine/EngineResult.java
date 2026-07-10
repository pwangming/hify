package com.hify.workflow.service.engine;

import java.util.Map;

/** 一次引擎执行的收敛结果：成功带最终输出；失败带失败节点与用户可读原因（写入 run.error_message）。 */
public record EngineResult(boolean succeeded, Map<String, Object> outputs,
                           String failedNodeId, String errorMessage) {

    public static EngineResult success(Map<String, Object> outputs) {
        return new EngineResult(true, outputs, null, null);
    }

    public static EngineResult failure(String failedNodeId, String errorMessage) {
        return new EngineResult(false, null, failedNodeId, errorMessage);
    }
}
