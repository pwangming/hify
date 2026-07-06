package com.hify.knowledge.api;

import java.util.List;

/**
 * knowledge 模块对外门面（一个模块最多一个 Facade，Modulith 强制）。
 * 刻意只暴露两参 retrieve：topK/相似度阈值是 knowledge 内部的全局配置
 * （hify.knowledge.retrieval.*），调用方不需要也不应该看见；带显式参数的入口是模块内部
 * RetrievalService，仅命中测试端点覆写用。
 */
public interface KnowledgeFacade {

    /**
     * 向量检索：返回按相似度降序、已过阈值的命中段（最多全局 topK 条）。空/null datasetIds 返回空列表。
     * 未配 embedding 模型（12006）/供应商故障（12003/12004）抛 BizException——降级与否由调用方决定
     * （conversation 降级继续答；命中测试原样抛给前端）。
     */
    List<RetrievedChunk> retrieve(List<Long> datasetIds, String query);

    /** 绑定校验：datasetIds 全部存在且未删则通过，否则抛 BizException(10005)。空/null 直接通过。app 创建/编辑时调。 */
    void validateDatasetIds(List<Long> datasetIds);
}
