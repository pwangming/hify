/**
 * knowledge —— 知识库、文档分段、向量化、检索。
 *
 * <p>依赖白名单（code-organization.md 第 1 节）：provider（取 EmbeddingModel / 检索用模型）+ common、infra。
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"provider::api", "common", "infra"}
)
package com.hify.knowledge;
