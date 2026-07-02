package com.hify.knowledge.dto;

/** 分段预览视图。position 从 1 起。 */
public record ChunkResponse(Long id, Integer position, String content) {
}
