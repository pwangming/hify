package com.hify.knowledge.dto;

import java.time.OffsetDateTime;

/** 文档视图。不含原始文件内容（bytea 大列不进列表/详情响应）。Long 序列化为 string；Integer 保持数字。 */
public record DocumentResponse(
        Long id,
        Long datasetId,
        String name,
        String fileType,
        Long fileSize,
        String status,
        Integer chunkCount,
        String errorMessage,
        OffsetDateTime createTime,
        OffsetDateTime updateTime) {
}
