package com.hify.workflow.dto;

import java.util.Map;

/**
 * 画布节点：type ∈ NodeType；data 为节点私有配置（llm: modelId/systemPrompt/userPrompt；
 * condition: left/operator/right；start: inputs；end: outputs）。
 * position：画布坐标 {x,y}，引擎与校验不读，只保证 jsonb 往返不丢（W1 留账，spec §2）。
 */
public record GraphNode(String id, String type, Map<String, Object> data, Map<String, Object> position) {

    /** 无画布坐标的节点（API 直存草稿、测试构造用）。 */
    public GraphNode(String id, String type, Map<String, Object> data) {
        this(id, type, data, null);
    }
}
