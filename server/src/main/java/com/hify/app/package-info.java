/**
 * app —— 应用元数据、API Key。
 *
 * <p>依赖白名单（code-organization.md 第 1 节）：provider、knowledge、tool（仅校验引用是否存在）
 * + common、infra。
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"provider::api", "knowledge::api", "tool::api", "common", "infra"}
)
package com.hify.app;
