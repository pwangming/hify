package com.hify.tool.controller;

import com.hify.common.Result;
import com.hify.infra.security.CurrentUserHolder;
import com.hify.tool.dto.CreateToolRequest;
import com.hify.tool.dto.PreviewToolRequest;
import com.hify.tool.dto.ToolAdminDetailResponse;
import com.hify.tool.dto.ToolPreviewResponse;
import com.hify.tool.dto.ToolAdminResponse;
import com.hify.tool.dto.UpdateToolRequest;
import com.hify.tool.service.ToolAdminService;
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
 * 自定义工具注册表 admin 接口（仅 Admin，SecurityConfig 的 hasRole(ADMIN) 统一拦 /api/v1/admin/**）。
 * 协议层无业务逻辑、无 @Transactional、不注入 Mapper。启停用动作子资源 POST（不用 PATCH）。
 */
@RestController
@RequestMapping("/api/v1/admin/tool/tools")
public class AdminToolController {

    private final ToolAdminService toolAdminService;

    public AdminToolController(ToolAdminService toolAdminService) {
        this.toolAdminService = toolAdminService;
    }

    @GetMapping
    public Result<List<ToolAdminResponse>> list() {
        return Result.ok(toolAdminService.list());
    }

    @PostMapping("/preview")
    public Result<ToolPreviewResponse> preview(@Valid @RequestBody PreviewToolRequest request) {
        return Result.ok(toolAdminService.preview(request.specText()));
    }

    @PostMapping
    public Result<ToolAdminResponse> create(@Valid @RequestBody CreateToolRequest request) {
        return Result.ok(toolAdminService.create(request, CurrentUserHolder.current()));
    }

    @GetMapping("/{id}")
    public Result<ToolAdminDetailResponse> get(@PathVariable Long id) {
        return Result.ok(toolAdminService.get(id));
    }

    @PutMapping("/{id}")
    public Result<ToolAdminResponse> update(@PathVariable Long id,
                                            @Valid @RequestBody UpdateToolRequest request) {
        return Result.ok(toolAdminService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        toolAdminService.delete(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        toolAdminService.enable(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        toolAdminService.disable(id);
        return Result.ok(null);
    }
}
