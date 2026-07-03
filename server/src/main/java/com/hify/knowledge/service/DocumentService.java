package com.hify.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.common.page.PageResult;
import com.hify.infra.security.CurrentUser;
import com.hify.knowledge.constant.KnowledgeError;
import com.hify.knowledge.dto.ChunkResponse;
import com.hify.knowledge.dto.DocumentResponse;
import com.hify.knowledge.entity.Dataset;
import com.hify.knowledge.entity.KbChunk;
import com.hify.knowledge.entity.KbDocument;
import com.hify.knowledge.mapper.DatasetMapper;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.knowledge.mapper.KbDocumentMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;

/**
 * 文档业务逻辑：上传落库 pending，处理全在 DocumentProcessJob 异步流水线；列表、删除、分段预览。
 * 权限随所属 dataset 判（owner/Admin，复用 DatasetService.assertCanModify）。
 */
@Service
public class DocumentService {

    private static final int NAME_MAX = 200;

    private final DatasetMapper datasetMapper;
    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final DocumentProcessJob processJob;
    private final int chunkSize;
    private final int chunkOverlap;

    public DocumentService(DatasetMapper datasetMapper,
                           KbDocumentMapper documentMapper,
                           KbChunkMapper chunkMapper,
                           ApplicationEventPublisher eventPublisher,
                           DocumentProcessJob processJob,
                           @Value("${hify.knowledge.chunk-size}") int chunkSize,
                           @Value("${hify.knowledge.chunk-overlap}") int chunkOverlap) {
        this.datasetMapper = datasetMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.eventPublisher = eventPublisher;
        this.processJob = processJob;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    @Transactional
    public DocumentResponse upload(Long datasetId, MultipartFile file, CurrentUser current) {
        Dataset dataset = loadDatasetOrThrow(datasetId);
        DatasetService.assertCanModify(dataset, current);

        String name = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
        if (name.length() > NAME_MAX) {
            throw new BizException(CommonError.PARAM_INVALID, "文件名不能超过 " + NAME_MAX + " 个字符");
        }
        String fileType = fileTypeOf(name);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(KnowledgeError.DOCUMENT_CONTENT_EMPTY, "文档读取失败", e);
        }

        KbDocument doc = new KbDocument();
        doc.setDatasetId(datasetId);
        doc.setName(name);
        doc.setFileType(fileType);
        doc.setFileSize((long) bytes.length);
        doc.setContent(bytes);
        doc.setStatus("pending");
        doc.setChunkCount(0);
        doc.setChunkSize(chunkSize);
        doc.setChunkOverlap(chunkOverlap);
        documentMapper.insert(doc);

        eventPublisher.publishEvent(new DocumentUploadedEvent(doc.getId()));
        return toResponse(doc);
    }

    public void retryDocument(Long id, CurrentUser current) {
        KbDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BizException(CommonError.NOT_FOUND, "文档不存在");
        }
        Dataset dataset = datasetMapper.selectById(doc.getDatasetId());
        if (dataset == null) {
            throw new BizException(CommonError.NOT_FOUND, "知识库不存在");
        }
        DatasetService.assertCanModify(dataset, current);
        if (documentMapper.claimStatus(id, "failed") == 0) {
            throw new BizException(KnowledgeError.DOCUMENT_STATE_CONFLICT);
        }
        processJob.processRetry(id);
    }

    public PageResult<DocumentResponse> pageDocuments(Long datasetId, int page, int size) {
        assertPageParams(page, size);
        loadDatasetOrThrow(datasetId);
        Page<KbDocument> result = documentMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<KbDocument>()
                        // 大列不进列表（database-standards）：显式排除 content
                        .select(KbDocument.class, info -> !"content".equals(info.getColumn()))
                        .eq(KbDocument::getDatasetId, datasetId)
                        .orderByDesc(KbDocument::getId));
        return PageResult.of(result.getRecords().stream().map(this::toResponse).toList(),
                result.getTotal(), page, size);
    }

    @Transactional
    public void deleteDocument(Long id, CurrentUser current) {
        KbDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            return; // 幂等
        }
        Dataset dataset = datasetMapper.selectById(doc.getDatasetId());
        if (dataset == null) {
            return; // 所属库已删（级联应已删本文档），按幂等处理
        }
        DatasetService.assertCanModify(dataset, current);
        documentMapper.deleteById(id);
        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, id));
    }

    public PageResult<ChunkResponse> pageChunks(Long documentId, int page, int size) {
        assertPageParams(page, size);
        if (documentMapper.selectById(documentId) == null) {
            throw new BizException(CommonError.NOT_FOUND, "文档不存在");
        }
        Page<KbChunk> result = chunkMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getDocumentId, documentId)
                        .orderByAsc(KbChunk::getPosition));
        return PageResult.of(result.getRecords().stream()
                        .map(c -> new ChunkResponse(c.getId(), c.getPosition(), c.getContent())).toList(),
                result.getTotal(), page, size);
    }

    private Dataset loadDatasetOrThrow(Long datasetId) {
        Dataset dataset = datasetMapper.selectById(datasetId);
        if (dataset == null) {
            throw new BizException(CommonError.NOT_FOUND, "知识库不存在");
        }
        return dataset;
    }

    /** 扩展名判定（大小写不敏感）：仅 txt/md；其余 15004。不猜 MIME。 */
    private String fileTypeOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt")) {
            return "txt";
        }
        if (lower.endsWith(".md")) {
            return "md";
        }
        throw new BizException(KnowledgeError.DOCUMENT_FORMAT_UNSUPPORTED);
    }

    private void assertPageParams(int page, int size) {
        if (page < 1 || size < 1 || size > 100 || (long) page * size > 10_000) {
            throw new BizException(CommonError.PARAM_INVALID, "分页参数非法或过深，请用筛选条件缩小范围");
        }
    }

    private DocumentResponse toResponse(KbDocument d) {
        return new DocumentResponse(d.getId(), d.getDatasetId(), d.getName(), d.getFileType(),
                d.getFileSize(), d.getStatus(), d.getChunkCount(), d.getErrorMessage(),
                d.getCreateTime(), d.getUpdateTime());
    }
}
