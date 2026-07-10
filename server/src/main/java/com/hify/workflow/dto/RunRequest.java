package com.hify.workflow.dto;

import java.util.Map;

/** 触发运行请求。inputs 可空（无输入的工作流）；必填项校验依据 start 节点声明在 service 做。 */
public record RunRequest(Map<String, Object> inputs) {
}
