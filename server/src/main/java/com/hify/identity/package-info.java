/**
 * identity —— 用户、角色、登录。
 *
 * <p>依赖白名单（code-organization.md 第 1 节）：仅 common、infra，不依赖任何业务模块。
 * 注意：其他模块<b>禁止依赖 identity</b>，「当前用户」一律从 infra 的 SecurityContextHolder 取。
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "infra"}
)
package com.hify.identity;
