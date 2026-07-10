package com.hify.workflow.dto;

import jakarta.validation.constraints.NotNull;

/** 保存草稿请求（PUT 全量）：body 即整个画布。节点/连线的结构性校验在 GraphValidator，不在注解层。 */
public record SaveDraftRequest(@NotNull GraphDef graph) {
}
