/**
 * provider —— 模型供应商、模型实例、ChatClient/EmbeddingModel 工厂。
 *
 * <p>依赖白名单（code-organization.md 第 1 节）：仅 common、infra。
 * 例外：本模块 Facade 允许在签名中使用 Spring AI 类型（ChatClient、EmbeddingModel）。
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "infra"}
)
package com.hify.provider;
