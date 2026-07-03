package com.hify.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.knowledge.constant.KnowledgeError;
import com.hify.knowledge.entity.KbChunk;
import com.hify.knowledge.entity.KbDocument;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.knowledge.mapper.KbDocumentMapper;
import com.hify.provider.api.ProviderFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 文档处理流水线：提取→分段→分批嵌入→ready/failed。 */
@Service
public class DocumentProcessJob {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessJob.class);

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final DocumentProcessStore store;
    private final ProviderFacade providerFacade;
    private final ReembedGate gate;
    private final int batchSize;

    public DocumentProcessJob(KbDocumentMapper documentMapper,
                              KbChunkMapper chunkMapper,
                              DocumentProcessStore store,
                              ProviderFacade providerFacade,
                              ReembedGate gate,
                              @Value("${hify.knowledge.embedding-batch-size}") int batchSize) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.store = store;
        this.providerFacade = providerFacade;
        this.gate = gate;
        this.batchSize = batchSize;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        if (documentMapper.claimStatus(event.documentId(), "pending") == 0) {
            return;
        }
        runOnce(event.documentId());
    }

    @Async
    public void processRetry(Long documentId) {
        runOnce(documentId);
    }

    @Async
    public void reembedAll() {
        try {
            chunkMapper.clearAllEmbeddings();
            for (Long docId : documentMapper.selectReembedTargetIds()) {
                if (documentMapper.claimForReembed(docId) == 1) {
                    runOnce(docId);
                }
            }
        } finally {
            gate.finish();
        }
    }

    public void runOnce(Long documentId) {
        try {
            KbDocument doc = documentMapper.selectById(documentId);
            if (doc == null) {
                return;
            }
            if (chunkMapper.selectCount(new LambdaQueryWrapper<KbChunk>()
                    .eq(KbChunk::getDocumentId, documentId)) == 0) {
                extractAndChunk(doc);
            }
            embedPending(documentId);
            documentMapper.markReady(documentId);
        } catch (BizException e) {
            documentMapper.markFailed(documentId, e.getMessage());
        } catch (Exception e) {
            log.error("文档处理失败 documentId={}", documentId, e);
            documentMapper.markFailed(documentId, "处理失败，请重试");
        }
    }

    private void extractAndChunk(KbDocument doc) {
        String text = new String(doc.getContent(), StandardCharsets.UTF_8);
        List<String> pieces = TextChunker.split(text, doc.getChunkSize(), doc.getChunkOverlap());
        if (pieces.isEmpty()) {
            throw new BizException(KnowledgeError.DOCUMENT_CONTENT_EMPTY);
        }
        store.saveChunks(doc, pieces);
    }

    private void embedPending(Long documentId) {
        List<KbChunk> pending = chunkMapper.selectUnembedded(documentId);
        if (pending.isEmpty()) {
            return;
        }
        EmbeddingModel model = providerFacade.getEmbeddingModel();
        for (int from = 0; from < pending.size(); from += batchSize) {
            List<KbChunk> batch = pending.subList(from, Math.min(from + batchSize, pending.size()));
            List<float[]> vectors = model.embed(batch.stream().map(KbChunk::getContent).toList());
            store.writeEmbeddings(batch, vectors);
        }
    }
}
