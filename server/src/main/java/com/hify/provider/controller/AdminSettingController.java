package com.hify.provider.controller;

import com.hify.common.Result;
import com.hify.provider.dto.EmbeddingSettingResponse;
import com.hify.provider.dto.UpdateEmbeddingSettingRequest;
import com.hify.provider.service.EmbeddingSettingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** admin 系统设置接口（仅 Admin）。 */
@RestController
@RequestMapping("/api/v1/admin/provider")
public class AdminSettingController {

    private final EmbeddingSettingService embeddingSettingService;

    public AdminSettingController(EmbeddingSettingService embeddingSettingService) {
        this.embeddingSettingService = embeddingSettingService;
    }

    @GetMapping("/settings/embedding-model")
    public Result<EmbeddingSettingResponse> getEmbeddingModel() {
        return Result.ok(embeddingSettingService.get());
    }

    @PutMapping("/settings/embedding-model")
    public Result<EmbeddingSettingResponse> putEmbeddingModel(
            @Valid @RequestBody UpdateEmbeddingSettingRequest request) {
        return Result.ok(embeddingSettingService.save(request.modelId()));
    }
}
