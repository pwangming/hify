/**
 * infra —— 技术基础设施：安全(JWT)、MyBatis/缓存/Jackson 配置、全局异常处理、限流。
 *
 * <p>声明为 OPEN 模块（code-organization.md 第 5.1 节）：技术组件（如 SecurityContextHolder、
 * 统一 executor）需被各模块直接注入使用，不走 api 命名接口。infra 自身只允许依赖 common。
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.hify.infra;
