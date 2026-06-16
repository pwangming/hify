/**
 * tool —— 工具注册表、内置工具、OpenAPI 工具、MCP 接入。
 *
 * <p>依赖白名单（code-organization.md 第 1 节）：仅 common、infra。
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "infra"}
)
package com.hify.tool;
