package com.hify.provider.controller;

import com.hify.common.Result;
import com.hify.provider.api.dto.ModelTestResponse;
import com.hify.provider.dto.CreateModelRequest;
import com.hify.provider.dto.ModelResponse;
import com.hify.provider.dto.UpdateModelRequest;
import com.hify.provider.service.AiModelService;
import com.hify.provider.service.ModelConnectionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * admin 模型管理接口（仅 Admin）。混合路由：列表/创建挂供应商下、单条操作走顶级
 * （api-standards "子资源有了自己的 id 就升为顶级"）。类前缀取两族公共段 /api/v1/admin/provider。
 * 协议层：@Valid + 调 service + 包 Result；无业务/无 try-catch/无 @Transactional/不注入 Mapper。
 */
@RestController
@RequestMapping("/api/v1/admin/provider")
public class AdminModelController {

    private final AiModelService aiModelService;
    private final ModelConnectionService modelConnectionService;

    public AdminModelController(AiModelService aiModelService,
                               ModelConnectionService modelConnectionService) {
        this.aiModelService = aiModelService;
        this.modelConnectionService = modelConnectionService;
    }

    @GetMapping("/providers/{providerId}/models")
    public Result<List<ModelResponse>> list(@PathVariable Long providerId) {
        return Result.ok(aiModelService.listByProvider(providerId));
    }

    @PostMapping("/providers/{providerId}/models")
    public Result<ModelResponse> create(@PathVariable Long providerId,
                                        @Valid @RequestBody CreateModelRequest request) {
        return Result.ok(aiModelService.create(providerId, request));
    }

    @PutMapping("/models/{id}")
    public Result<ModelResponse> update(@PathVariable Long id,
                                        @Valid @RequestBody UpdateModelRequest request) {
        return Result.ok(aiModelService.update(id, request));
    }

    @DeleteMapping("/models/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        aiModelService.delete(id);
        return Result.ok(null);
    }

    @PostMapping("/models/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        aiModelService.enable(id);
        return Result.ok(null);
    }

    @PostMapping("/models/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        aiModelService.disable(id);
        return Result.ok(null);
    }

    /** 测试连通：发一句最短 prompt 真实调用该模型，验证 Key/baseUrl/网络。失败按韧性映射（12002/12003/12004）。 */
    @PostMapping("/models/{id}/test")
    public Result<ModelTestResponse> test(@PathVariable Long id) {
        return Result.ok(modelConnectionService.test(id));
    }
}
