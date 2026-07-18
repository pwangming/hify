/**
 * usage —— 调用日志、Token 统计、配额。
 *
 * <p>依赖白名单（code-organization.md 第 1 节）：provider::api、common、infra。
 * 白名单含 provider::api：看板费用计算取模型单价（2026-07-17 拍板，仅此一条，名称解析走前端拼装）。
 * 计量数据通过监听 TokenUsedEvent / ToolInvokedEvent 写入，禁止其他模块同步调用 usage 写入。
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"provider::api", "common", "infra"}
)
package com.hify.usage;
