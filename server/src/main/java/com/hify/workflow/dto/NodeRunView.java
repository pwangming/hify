package com.hify.workflow.dto;

import java.time.OffsetDateTime;
import java.util.Map;

/** 节点日志视图（随 RunResponse 返回，按执行顺序）。 */
public record NodeRunView(Long id, String nodeId, String nodeType, String status,
                          Map<String, Object> inputs, Map<String, Object> outputs,
                          String errorMessage, Long elapsedMs, OffsetDateTime createTime) {
}
