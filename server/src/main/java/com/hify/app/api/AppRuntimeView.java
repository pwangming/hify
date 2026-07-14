package com.hify.app.api;

import java.util.List;

/**
 * 应用运行时视图（跨模块）：conversation 取它发起对话。
 * modelId 必非空；systemPrompt 为人设（可空）；datasetIds 恒非 null（无绑定=空列表）；
 * agentEnabled=true 时 conversation 走 Agent 工具调用循环；toolIds 为 Agent 勾选启用的工具，恒非 null。
 */
public record AppRuntimeView(Long appId, Long modelId, String systemPrompt,
                             List<Long> datasetIds, boolean agentEnabled, List<Long> toolIds) {
}
