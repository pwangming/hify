package com.hify.workflow.dto;

import java.util.Map;

/** 画布节点：type ∈ NodeType；data 为节点私有配置（llm: modelId/systemPrompt/userPrompt；start: inputs；end: outputs）。 */
public record GraphNode(String id, String type, Map<String, Object> data) {
}
