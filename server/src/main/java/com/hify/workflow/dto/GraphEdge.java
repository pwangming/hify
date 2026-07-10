package com.hify.workflow.dto;

/** 画布连线。预留 sourceHandle 给条件分支轮（W1 不解析）。 */
public record GraphEdge(String source, String target) {
}
