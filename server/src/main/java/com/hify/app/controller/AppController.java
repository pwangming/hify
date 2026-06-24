package com.hify.app.controller;

import com.hify.app.dto.AppResponse;
import com.hify.app.dto.CreateAppRequest;
import com.hify.app.dto.UpdateAppRequest;
import com.hify.app.service.AppService;
import com.hify.common.Result;
import com.hify.common.page.PageResult;
import com.hify.infra.security.CurrentUserHolder;
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

/**
 * 应用管理接口（成员族 /api/v1/app/**，任意登录用户可访问；团队共享权限在 service 判 owner+Admin）。
 * 协议层：@Valid 校验 → 取当前用户 → 调 service → 包 Result；无业务逻辑、无 try-catch、无 @Transactional。
 */
@RestController
@RequestMapping("/api/v1/app/apps")
public class AppController {

    private final AppService appService;

    public AppController(AppService appService) {
        this.appService = appService;
    }

    @GetMapping
    public Result<PageResult<AppResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(appService.page(keyword, type, page, size));
    }

    @GetMapping("/{id}")
    public Result<AppResponse> get(@PathVariable Long id) {
        return Result.ok(appService.get(id));
    }

    @PostMapping
    public Result<AppResponse> create(@Valid @RequestBody CreateAppRequest request) {
        return Result.ok(appService.create(request, CurrentUserHolder.current()));
    }

    @PutMapping("/{id}")
    public Result<AppResponse> update(@PathVariable Long id,
                                      @Valid @RequestBody UpdateAppRequest request) {
        return Result.ok(appService.update(id, request, CurrentUserHolder.current()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        appService.delete(id, CurrentUserHolder.current());
        return Result.ok(null);
    }

    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        appService.enable(id, CurrentUserHolder.current());
        return Result.ok(null);
    }

    @PostMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        appService.disable(id, CurrentUserHolder.current());
        return Result.ok(null);
    }
}
