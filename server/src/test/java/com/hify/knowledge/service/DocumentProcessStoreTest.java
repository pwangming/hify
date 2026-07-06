package com.hify.knowledge.service;

import com.hify.knowledge.entity.KbChunk;
import com.hify.knowledge.entity.KbDocument;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** K3 留账兑现：saveChunks（Db.saveBatch 组装）与 vectorLiteral→writeEmbeddings 写真库直测。 */
class DocumentProcessStoreTest extends PgIntegrationTest {

    @Autowired
    private DocumentProcessStore store;
    @Autowired
    private KbChunkMapper chunkMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private KbDocument doc;

    @BeforeEach
    void seed() {
        Long datasetId = jdbc.queryForObject(
                "insert into dataset(name, owner_id) values ('K4存储直测库', 1) returning id", Long.class);
        Long docId = jdbc.queryForObject("""
                insert into kb_document(dataset_id, name, file_type, file_size, content, status, chunk_size, chunk_overlap)
                values (?, 'store.txt', 'txt', 1, ?, 'processing', 500, 50) returning id""",
                Long.class, datasetId, new byte[0]);
        doc = new KbDocument();
        doc.setId(docId);
        doc.setDatasetId(datasetId);
    }

    @Test
    void saveChunks_批量落库且position从1起_并更新chunkCount() {
        store.saveChunks(doc, List.of("段一", "段二", "段三"));
        assertEquals(3, jdbc.queryForObject(
                "select count(*) from kb_chunk where document_id = ?", Integer.class, doc.getId()));
        assertEquals(1, jdbc.queryForObject(
                "select position from kb_chunk where document_id = ? and content = '段一'", Integer.class, doc.getId()));
        assertEquals(3, jdbc.queryForObject(
                "select chunk_count from kb_document where id = ?", Integer.class, doc.getId()));
    }

    @Test
    void writeEmbeddings_vectorLiteral写入真库_维度1024可回读() {
        store.saveChunks(doc, List.of("待嵌段"));
        List<KbChunk> pending = chunkMapper.selectUnembedded(doc.getId());
        assertEquals(1, pending.size());
        float[] v = new float[1024];
        v[0] = 0.5f;
        v[1023] = -0.25f;
        store.writeEmbeddings(pending, List.of(v));
        assertEquals(1024, jdbc.queryForObject(
                "select vector_dims(embedding) from kb_chunk where id = ?", Integer.class, pending.get(0).getId()));
        assertEquals(0, chunkMapper.selectUnembedded(doc.getId()).size());
    }
}
