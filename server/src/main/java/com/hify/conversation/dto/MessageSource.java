package com.hify.conversation.dto;

/**
 * 引用来源快照（一条命中段）。三处共用：MessageView.sources / SSE sources 事件 / message.sources jsonb。
 * chunkId/documentId 全局 Jackson -> string；score 为相似度 0~1，保持 number；preview 为 content 截断快照（不含全文）。
 * documentName/preview 存答题那一刻的快照，文档后续改名/删除不影响历史留痕。
 */
public record MessageSource(
        Long chunkId,
        Long documentId,
        String documentName,
        double score,
        String preview) {
}
