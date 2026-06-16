/**
 * usage —— 调用日志、Token 统计、配额。
 *
 * <p>依赖白名单（code-organization.md 第 1 节）：仅 common、infra。
 * 计量数据通过监听 TokenUsedEvent / ToolInvokedEvent 写入，禁止其他模块同步调用 usage 写入。
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "infra"}
)
package com.hify.usage;
