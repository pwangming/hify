package com.hify.knowledge.service;

import com.hify.common.exception.BizException;
import com.hify.knowledge.api.RetrievedChunk;
import com.hify.knowledge.dto.ChunkHit;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.provider.api.ProviderFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    private KbChunkMapper chunkMapper;
    private ProviderFacade providerFacade;
    private EmbeddingModel embeddingModel;
    private RetrievalService service;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(KbChunkMapper.class);
        providerFacade = mock(ProviderFacade.class);
        embeddingModel = mock(EmbeddingModel.class);
        // 全局默认 topK=4、阈值=0.3（对应 yml 缺省）
        service = new RetrievalService(chunkMapper, providerFacade, 4, 0.3);
    }

    private static ChunkHit hit(long id, String content, double score) {
        ChunkHit h = new ChunkHit();
        h.setId(id);
        h.setDocumentId(2L);
        h.setDocumentName("a.txt");
        h.setContent(content);
        h.setScore(score);
        return h;
    }

    private void stubEmbedding() {
        when(providerFacade.getEmbeddingModel()).thenReturn(embeddingModel);
        when(embeddingModel.embed(eq("问题"))).thenReturn(new float[]{0.1f, 0.2f});
    }

    @Test
    void 空datasetIds_短路返回空_不调embedding() {
        assertEquals(List.of(), service.retrieve(List.of(), "问题"));
        assertEquals(List.of(), service.retrieve(null, "问题"));
        verify(providerFacade, never()).getEmbeddingModel();
    }

    @Test
    void 命中按阈值过滤_低于0点3丢弃_并映射为RetrievedChunk() {
        stubEmbedding();
        when(chunkMapper.searchByVector(eq(List.of(9L)), anyString(), eq(4)))
                .thenReturn(List.of(hit(1L, "高分段", 0.83), hit(2L, "低分段", 0.1)));
        List<RetrievedChunk> out = service.retrieve(List.of(9L), "问题");
        assertEquals(1, out.size());
        assertEquals(new RetrievedChunk(1L, 2L, "a.txt", "高分段", 0.83), out.get(0));
    }

    @Test
    void 查询向量以字面量传给Mapper() {
        stubEmbedding();
        when(chunkMapper.searchByVector(eq(List.of(9L)), eq("[0.1,0.2]"), eq(4))).thenReturn(List.of());
        assertTrue(service.retrieve(List.of(9L), "问题").isEmpty());
        verify(chunkMapper).searchByVector(eq(List.of(9L)), eq("[0.1,0.2]"), eq(4));
    }

    @Test
    void 显式topK与阈值覆盖默认值() {
        stubEmbedding();
        when(chunkMapper.searchByVector(eq(List.of(9L)), anyString(), eq(10)))
                .thenReturn(List.of(hit(1L, "低分也保留", 0.05)));
        List<RetrievedChunk> out = service.retrieve(List.of(9L), "问题", 10, 0.0);
        assertEquals(1, out.size());
    }

    @Test
    void embedding异常原样透传_降级由调用方决定() {
        when(providerFacade.getEmbeddingModel())
                .thenThrow(new BizException(com.hify.common.exception.CommonError.DEPENDENCY_UNAVAILABLE, "未配置"));
        assertThrows(BizException.class, () -> service.retrieve(List.of(9L), "问题"));
    }
}
