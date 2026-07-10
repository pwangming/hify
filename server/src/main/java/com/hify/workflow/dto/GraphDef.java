package com.hify.workflow.dto;

import java.util.List;

/**
 * 画布定义（workflow_def.graph jsonb 的 Java 形态），字段名对齐 Vue Flow（画布轮零转换）。
 * 未来画布轮 additive 加 position 等字段；未知字段全局忽略（Jackson 配置）。
 */
public record GraphDef(List<GraphNode> nodes, List<GraphEdge> edges) {
}
