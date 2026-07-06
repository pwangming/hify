package com.hify.knowledge.service;

import com.hify.knowledge.api.RetrievedChunk;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.provider.api.ProviderFacade;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量检索：query → embedding（批量池韧性，K3 现成）→ 手写 SQL 取 topK → Java 层按阈值过滤。
 * 不带 @Transactional——embed 是真实外部 IO，检索 SQL 是只读单查。
 * 阈值在 Java 层过滤（spec 决策 10）：写进 SQL where 会干扰 HNSW 索引走法。
 */
@Service
public class RetrievalService {

    private final KbChunkMapper chunkMapper;
    private final ProviderFacade providerFacade;
    private final int defaultTopK;
    private final double defaultScoreThreshold;

    public RetrievalService(KbChunkMapper chunkMapper, ProviderFacade providerFacade,
                            @Value("${hify.knowledge.retrieval.top-k}") int defaultTopK,
                            @Value("${hify.knowledge.retrieval.score-threshold}") double defaultScoreThreshold) {
        this.chunkMapper = chunkMapper;
        this.providerFacade = providerFacade;
        this.defaultTopK = defaultTopK;
        this.defaultScoreThreshold = defaultScoreThreshold;
    }

    /** 默认参数入口（Facade 委托）：topK/阈值用全局配置。 */
    public List<RetrievedChunk> retrieve(List<Long> datasetIds, String query) {
        return retrieve(datasetIds, query, defaultTopK, defaultScoreThreshold);
    }

    /** 显式参数入口（命中测试端点覆写用）。空 datasetIds 短路，不白跑 embedding API。 */
    public List<RetrievedChunk> retrieve(List<Long> datasetIds, String query, int topK, double scoreThreshold) {
        if (datasetIds == null || datasetIds.isEmpty()) {
            return List.of();
        }
        float[] qvec = providerFacade.getEmbeddingModel().embed(query);
        return chunkMapper.searchByVector(datasetIds, DocumentProcessStore.vectorLiteral(qvec), topK).stream()
                .filter(h -> h.getScore() >= scoreThreshold)
                .map(h -> new RetrievedChunk(h.getId(), h.getDocumentId(), h.getDocumentName(),
                        h.getContent(), h.getScore()))
                .toList();
    }

    int defaultTopK() {
        return defaultTopK;
    }

    double defaultScoreThreshold() {
        return defaultScoreThreshold;
    }
}
