package com.hify.provider.controller;

import com.hify.common.Result;
import com.hify.provider.dto.CreateProviderRequest;
import com.hify.provider.dto.ProviderResponse;
import com.hify.provider.dto.ProviderTestResponse;
import com.hify.provider.dto.UpdateProviderRequest;
import com.hify.provider.service.ModelConnectionService;
import com.hify.provider.service.ProviderService;
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
 * admin 模型供应商管理接口（仅 Admin）。路径在 /api/v1/admin/** 下，由 SecurityConfig 的
 * hasRole("ADMIN") 统一拦截，类上无需再加注解。协议层：@Valid 校验 → 调本模块 service → 包 Result；
 * 无业务逻辑、无 try-catch、无 @Transactional、不注入 Mapper（code-organization.md 第 2 节）。
 * 启停用动作子资源 POST（不用 PATCH）；删除/启停成功返回 Void，前端重拉列表刷新。
 */
@RestController
@RequestMapping("/api/v1/admin/provider/providers")
public class AdminProviderController {

    private final ProviderService providerService;
    private final ModelConnectionService modelConnectionService;

    public AdminProviderController(ProviderService providerService, ModelConnectionService modelConnectionService) {
        this.providerService = providerService;
        this.modelConnectionService = modelConnectionService;
    }

    @GetMapping
    public Result<List<ProviderResponse>> list() {
        return Result.ok(providerService.list());
    }

    @PostMapping
    public Result<ProviderResponse> create(@Valid @RequestBody CreateProviderRequest request) {
        return Result.ok(providerService.create(request));
    }

    @PutMapping("/{id}")
    public Result<ProviderResponse> update(@PathVariable Long id,
                                           @Valid @RequestBody UpdateProviderRequest request) {
        return Result.ok(providerService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        providerService.delete(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        providerService.enable(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        providerService.disable(id);
        return Result.ok(null);
    }

    /** 试连接：自动挑一个启用模型真实调用，成败都记入最近测试字段。失败按韧性映射（12002/12003/12004）。 */
    @PostMapping("/{id}/test")
    public Result<ProviderTestResponse> test(@PathVariable Long id) {
        return Result.ok(modelConnectionService.testProvider(id));
    }
}
