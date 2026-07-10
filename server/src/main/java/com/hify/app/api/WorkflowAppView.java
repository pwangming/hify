package com.hify.app.api;

/**
 * 工作流应用视图（跨模块）：workflow 模块取它做存在性/owner/启停判断。
 * 位于 api 顶层包（Modulith 只暴露顶层，见记忆 modulith-api-dto-not-consumable）。
 * enabled=false 仍返回（草稿可继续编辑，是否可运行由 workflow 侧判定）。
 */
public record WorkflowAppView(Long appId, Long ownerId, boolean enabled) {
}
