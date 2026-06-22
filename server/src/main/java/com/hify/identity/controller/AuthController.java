package com.hify.identity.controller;

import com.hify.common.Result;
import com.hify.identity.dto.LoginRequest;
import com.hify.identity.dto.LoginResponse;
import com.hify.identity.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录接口。协议转换层：只做 @Valid 校验、调用本模块 AuthService、组装 Result 返回；
 * 不写业务逻辑、不写 try-catch、不写 @Transactional、不注入 Mapper（code-organization.md 第 2 节）。
 * 路径 {@code /api/v1/identity/login} 在 SecurityConfig 中已配置 permitAll。
 */
@RestController
@RequestMapping("/api/v1/identity")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request.username(), request.password()));
    }
}
