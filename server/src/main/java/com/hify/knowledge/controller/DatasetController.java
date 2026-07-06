package com.hify.knowledge.controller;

import com.hify.common.Result;
import com.hify.common.page.PageResult;
import com.hify.infra.security.CurrentUserHolder;
import com.hify.knowledge.api.RetrievedChunk;
import com.hify.knowledge.dto.CreateDatasetRequest;
import com.hify.knowledge.dto.DatasetResponse;
import com.hify.knowledge.dto.RetrieveTestRequest;
import com.hify.knowledge.dto.UpdateDatasetRequest;
import com.hify.knowledge.service.DatasetService;
import com.hify.knowledge.service.RetrievalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库管理接口（成员族 /api/v1/knowledge/**，任意登录用户可访问；团队共享权限在 service 判 owner+Admin）。
 * 协议层：@Valid 校验 → 取当前用户 → 调 service → 包 Result；无业务逻辑、无 try-catch、无 @Transactional。
 */
@RestController
@RequestMapping("/api/v1/knowledge/datasets")
public class DatasetController {

    private final DatasetService datasetService;
    private final RetrievalService retrievalService;

    public DatasetController(DatasetService datasetService, RetrievalService retrievalService) {
        this.datasetService = datasetService;
        this.retrievalService = retrievalService;
    }

    @GetMapping
    public Result<PageResult<DatasetResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(datasetService.page(keyword, page, size));
    }

    @GetMapping("/{id}")
    public Result<DatasetResponse> get(@PathVariable Long id) {
        return Result.ok(datasetService.get(id));
    }

    @PostMapping
    public Result<DatasetResponse> create(@Valid @RequestBody CreateDatasetRequest request) {
        return Result.ok(datasetService.create(request, CurrentUserHolder.current()));
    }

    @PutMapping("/{id}")
    public Result<DatasetResponse> update(@PathVariable Long id,
                                          @Valid @RequestBody UpdateDatasetRequest request) {
        return Result.ok(datasetService.update(id, request, CurrentUserHolder.current()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        datasetService.delete(id, CurrentUserHolder.current());
        return Result.ok(null);
    }

    /** 命中测试（检索调试，不走 LLM）。团队共享读操作，登录即可；不降级，检索故障原样抛。 */
    @PostMapping("/{id}/retrieve")
    public Result<List<RetrievedChunk>> retrieve(@PathVariable Long id,
                                                 @Valid @RequestBody RetrieveTestRequest request) {
        return Result.ok(retrievalService.retrieveTest(id, request.query(), request.topK(), request.scoreThreshold()));
    }
}
