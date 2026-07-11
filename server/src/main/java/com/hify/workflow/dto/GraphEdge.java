package com.hify.workflow.dto;

/**
 * 画布连线。sourceHandle：condition 节点出口标记（"true"/"false"），普通边为 null；
 * W3a 起引擎按它选路（spec §2），字段名对齐 Vue Flow。
 */
public record GraphEdge(String source, String target, String sourceHandle) {

    /** 普通边（无分支出口）。 */
    public GraphEdge(String source, String target) {
        this(source, target, null);
    }
}
