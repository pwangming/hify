package com.hify.knowledge.controller;

import com.hify.common.Result;
import com.hify.common.page.PageResult;
import com.hify.infra.security.CurrentUserHolder;
import com.hify.knowledge.dto.ChunkResponse;
import com.hify.knowledge.dto.DocumentResponse;
import com.hify.knowledge.service.DocumentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档接口（成员族 /api/v1/knowledge/**）。documents 有自己的 id，删除/分段用顶级路由
 * /documents/{id}（api-standards §2.1「子资源有了自己的 id 就升为顶级」）。
 * 协议层：取当前用户 → 调 service → 包 Result；无业务逻辑、无 try-catch、无 @Transactional。
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/datasets/{datasetId}/documents")
    public Result<DocumentResponse> upload(@PathVariable Long datasetId,
                                           @RequestPart("file") MultipartFile file) {
        return Result.ok(documentService.upload(datasetId, file, CurrentUserHolder.current()));
    }

    @GetMapping("/datasets/{datasetId}/documents")
    public Result<PageResult<DocumentResponse>> listDocuments(
            @PathVariable Long datasetId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(documentService.pageDocuments(datasetId, page, size));
    }

    @DeleteMapping("/documents/{id}")
    public Result<Void> deleteDocument(@PathVariable Long id) {
        documentService.deleteDocument(id, CurrentUserHolder.current());
        return Result.ok(null);
    }

    @PostMapping("/documents/{id}/retry")
    public Result<Void> retryDocument(@PathVariable Long id) {
        documentService.retryDocument(id, CurrentUserHolder.current());
        return Result.ok(null);
    }

    @GetMapping("/documents/{id}/chunks")
    public Result<PageResult<ChunkResponse>> listChunks(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(documentService.pageChunks(id, page, size));
    }
}
