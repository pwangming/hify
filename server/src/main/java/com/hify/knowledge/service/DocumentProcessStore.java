package com.hify.knowledge.service;

import com.hify.knowledge.entity.KbChunk;
import com.hify.knowledge.entity.KbDocument;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.knowledge.mapper.KbDocumentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 流水线的事务写库操作。独立成类是因为 Spring 事务基于代理，DocumentProcessJob 内部自调用
 * 不过代理、@Transactional 会失效；拆出来经 bean 边界调用才生效。
 * 两个方法都只做本地写库——外部 IO（嵌入 API）在调用方事务外完成。
 */
@Service
public class DocumentProcessStore {

    private static final int BATCH_SIZE = 1000;

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;

    public DocumentProcessStore(KbDocumentMapper documentMapper, KbChunkMapper chunkMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    @Transactional
    public void saveChunks(KbDocument doc, List<String> pieces) {
        List<KbChunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            KbChunk chunk = new KbChunk();
            chunk.setDocumentId(doc.getId());
            chunk.setDatasetId(doc.getDatasetId());
            chunk.setPosition(i + 1);
            chunk.setContent(pieces.get(i));
            chunks.add(chunk);
        }
        // 多值 insert 每批 ≤ 1000（database-standards §2.1）；不用静态 Db.saveBatch——多测试上下文下
        // 会拿错 SqlSessionFactory，且 BATCH 执行器无法与本事务内的 SIMPLE 执行器共存
        for (int from = 0; from < chunks.size(); from += BATCH_SIZE) {
            chunkMapper.insertBatch(chunks.subList(from, Math.min(from + BATCH_SIZE, chunks.size())));
        }
        KbDocument patch = new KbDocument();
        patch.setId(doc.getId());
        patch.setChunkCount(pieces.size());
        documentMapper.updateById(patch);
    }

    @Transactional
    public void writeEmbeddings(List<KbChunk> batch, List<float[]> vectors) {
        for (int i = 0; i < batch.size(); i++) {
            chunkMapper.updateEmbedding(batch.get(i).getId(), vectorLiteral(vectors.get(i)));
        }
    }

    static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 12).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
