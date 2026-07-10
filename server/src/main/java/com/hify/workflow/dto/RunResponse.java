package com.hify.workflow.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 一次运行的完整视图（触发响应与详情共用）。失败时 status=failed + errorMessage，HTTP 仍 200（spec 拍板 #7）。 */
public record RunResponse(Long id, String status, Map<String, Object> inputs, Map<String, Object> outputs,
                          String errorMessage, Long elapsedMs, OffsetDateTime createTime,
                          List<NodeRunView> nodeRuns) {
}
