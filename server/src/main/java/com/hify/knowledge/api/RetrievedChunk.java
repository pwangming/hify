package com.hify.knowledge.api;

/**
 * 向量检索命中段（跨模块视图）：conversation 拼提示词、命中测试端点响应共用。
 * 放 api 顶层包（Modulith 1.4.1 不暴露 api/dto 子包）。score = 1 - 余弦距离 ∈ [~0,1]，越大越相关。
 */
public record RetrievedChunk(Long chunkId, Long documentId, String documentName, String content, double score) {
}
