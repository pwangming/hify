/**
 * identity —— 用户、角色、登录。
 *
 * <p>纯叶子模块：code-organization.md 第 1 节规定<b>禁止任何模块依赖 identity</b>，"当前用户"统一从
 * infra 的 {@code CurrentUserHolder.current()} 取。故 identity 刻意<b>不设 api/ 包、不设 Facade</b>——
 * 它对其他 Java 模块不暴露任何东西，只对外暴露 HTTP 接口（登录）。后人勿惯性给它造空 Facade。
 *
 * <p>依赖白名单仅 common、infra（infra 为 OPEN 模块，允许直接注入 JwtService / CurrentUser /
 * PasswordEncoder 等技术组件）。
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "infra"}
)
package com.hify.identity;
