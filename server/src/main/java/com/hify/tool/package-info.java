/**
 * tool —— 工具注册表、内置工具、OpenAPI 工具、MCP 接入。
 *
 * <p>依赖白名单（code-organization.md 第 1 节）：仅 common、infra。
 * 例外：本模块 Facade 允许在签名中使用 Spring AI 类型（ToolCallback）——与 provider 暴露 ChatClient 同理。
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "infra"}
)
package com.hify.tool;
