package com.hify.identity.controller;

import com.hify.common.Result;
import com.hify.identity.dto.ChangeRoleRequest;
import com.hify.identity.dto.CreateUserRequest;
import com.hify.identity.dto.ResetPasswordRequest;
import com.hify.identity.dto.UserView;
import com.hify.identity.service.AdminUserService;
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
 * admin 用户管理接口（仅 Admin）。协议层：@Valid 校验 → 调 AdminUserService → 包 Result；
 * 无业务逻辑、无 try-catch、无 @Transactional、不注入 Mapper（code-organization.md 第 2 节）。
 * 路径在 /api/v1/admin/** 下，由 SecurityConfig 的 hasRole("ADMIN") 统一拦截，无需类上再加注解。
 */
@RestController
@RequestMapping("/api/v1/admin/identity/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @PostMapping
    public Result<UserView> create(@Valid @RequestBody CreateUserRequest req) {
        return Result.ok(adminUserService.create(req.username(), req.password(), req.role()));
    }

    @GetMapping
    public Result<List<UserView>> list() {
        return Result.ok(adminUserService.list());
    }

    @PostMapping("/{id}/enable")
    public Result<UserView> enable(@PathVariable Long id) {
        return Result.ok(adminUserService.enable(id));
    }

    @PostMapping("/{id}/disable")
    public Result<UserView> disable(@PathVariable Long id) {
        return Result.ok(adminUserService.disable(id));
    }

    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest req) {
        adminUserService.resetPassword(id, req.password());
        return Result.ok(null);
    }

    @PutMapping("/{id}/role")
    public Result<UserView> changeRole(@PathVariable Long id, @Valid @RequestBody ChangeRoleRequest req) {
        return Result.ok(adminUserService.changeRole(id, req.role()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        adminUserService.delete(id);
        return Result.ok(null);
    }
}
