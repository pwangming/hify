package com.hify.identity.controller;

import com.hify.common.Result;
import com.hify.identity.dto.MeResponse;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.CurrentUserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户接口。GET /api/v1/identity/me：回显「我是谁」。
 *
 * <p>纯协议层：直接读安全上下文里的当前用户（CurrentUserHolder.current()，由 JWT 解析而来）组装响应，
 * 无业务逻辑、无 service、无 DB（身份信息本就在令牌里）。与 AuthController（/login，匿名）分开：
 * 本接口是受保护路由（不在 SecurityConfig permitAll 内，默认要求已认证）。
 */
@RestController
@RequestMapping("/api/v1/identity")
public class CurrentUserController {

    @GetMapping("/me")
    public Result<MeResponse> me() {
        CurrentUser current = CurrentUserHolder.current();
        return Result.ok(new MeResponse(current.userId(), current.username(), current.role()));
    }
}
