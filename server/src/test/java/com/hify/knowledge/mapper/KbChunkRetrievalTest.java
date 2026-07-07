package com.hify.knowledge.mapper;

import com.hify.knowledge.dto.ChunkHit;
import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手写向量检索 SQL 的真库测试：排序/分数/跨库过滤/软删与未嵌入排除/topK 截断（mock 测不到，K4 引 Testcontainers 的动因）。 */
class KbChunkRetrievalTest extends PgIntegrationTest {

    @Autowired
    private KbChunkMapper chunkMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private Long datasetA;
    private Long datasetB;
    private Long docA;
    private Long docB;

    /** 1024 维向量字面量：前两维为 (x, y)，其余为 0。夹角可控，余弦相似度可手算。 */
    private static String vec(double x, double y) {
        StringBuilder sb = new StringBuilder("[").append(x).append(',').append(y);
        sb.append(",0".repeat(1022));
        return sb.append(']').toString();
    }

    @BeforeEach
    void seed() {
        datasetA = insertDataset("K4检索A库");
        datasetB = insertDataset("K4检索B库");
        docA = insertDocument(datasetA, "a.txt");
        docB = insertDocument(datasetB, "b.txt");
    }

    private Long insertDataset(String name) {
        return jdbc.queryForObject(
                "insert into dataset(name, owner_id) values (?, 1) returning id", Long.class, name);
    }

    private Long insertDocument(Long datasetId, String name) {
        return jdbc.queryForObject("""
                insert into kb_document(dataset_id, name, file_type, file_size, content, status, chunk_size, chunk_overlap)
                values (?, ?, 'txt', 1, ?, 'ready', 500, 50) returning id""",
                Long.class, datasetId, name, new byte[0]);
    }

    private Long insertChunk(Long docId, Long dsId, int pos, String content, String vecLiteral) {
        return jdbc.queryForObject("""
                insert into kb_chunk(document_id, dataset_id, position, content, embedding)
                values (?, ?, ?, ?, ?::vector) returning id""",
                Long.class, docId, dsId, pos, content, vecLiteral);
    }

    @Test
    void 按相似度降序返回_带文档名与分数() {
        insertChunk(docA, datasetA, 1, "完全相关", vec(1, 0));   // cos=1.0
        insertChunk(docA, datasetA, 2, "部分相关", vec(1, 1));   // cos≈0.7071
        insertChunk(docA, datasetA, 3, "毫不相关", vec(0, 1));   // cos=0
        List<ChunkHit> hits = chunkMapper.searchByVector(List.of(datasetA), vec(1, 0), 10);
        assertEquals(3, hits.size());
        assertEquals("完全相关", hits.get(0).getContent());
        assertEquals("a.txt", hits.get(0).getDocumentName());
        assertEquals(1.0, hits.get(0).getScore(), 1e-4);
        assertEquals(0.7071, hits.get(1).getScore(), 1e-3);
        assertEquals("毫不相关", hits.get(2).getContent());
    }

    @Test
    void topK截断() {
        insertChunk(docA, datasetA, 1, "一", vec(1, 0));
        insertChunk(docA, datasetA, 2, "二", vec(1, 1));
        insertChunk(docA, datasetA, 3, "三", vec(0, 1));
        assertEquals(2, chunkMapper.searchByVector(List.of(datasetA), vec(1, 0), 2).size());
    }

    @Test
    void 跨库过滤_只搜指定知识库() {
        insertChunk(docA, datasetA, 1, "A库内容", vec(1, 0));
        insertChunk(docB, datasetB, 1, "B库内容", vec(1, 0));
        List<ChunkHit> onlyA = chunkMapper.searchByVector(List.of(datasetA), vec(1, 0), 10);
        assertEquals(1, onlyA.size());
        assertEquals("A库内容", onlyA.get(0).getContent());
        assertEquals(2, chunkMapper.searchByVector(List.of(datasetA, datasetB), vec(1, 0), 10).size());
    }

    @Test
    void 排除软删与未嵌入的分段() {
        insertChunk(docA, datasetA, 1, "正常段", vec(1, 0));
        Long deletedId = insertChunk(docA, datasetA, 2, "已删段", vec(1, 0));
        jdbc.update("update kb_chunk set deleted = true where id = ?", deletedId);
        jdbc.update("insert into kb_chunk(document_id, dataset_id, position, content) values (?, ?, 3, '未嵌入段')",
                docA, datasetA);
        List<ChunkHit> hits = chunkMapper.searchByVector(List.of(datasetA), vec(1, 0), 10);
        assertEquals(1, hits.size());
        assertEquals("正常段", hits.get(0).getContent());
        assertTrue(hits.stream().noneMatch(h -> h.getContent().contains("已删") || h.getContent().contains("未嵌入")));
    }
}
