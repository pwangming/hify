package com.hify.app.controller;

import com.hify.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统健康检查端点。
 *
 * <p>路由 {@code GET /api/v1/health}：供前端与负载均衡探活，无需认证。它不带 {@code <module>} 段，
 * 是 api-standards.md 第 1 节登记的「模块无关系统端点」例外（实现挂在 app 模块的 controller 下）。
 *
 * <p>返回统一 {@link Result} 信封：成功码 200、{@code data} 为纯文本提示。
 */
@RestController
public class HealthController {

    @GetMapping("/api/v1/health")
    public Result<String> health() {
        return Result.ok("Hify is running");
    }
}
