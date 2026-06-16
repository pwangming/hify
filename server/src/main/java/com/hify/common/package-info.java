/**
 * common —— 全局基础设施：Result、错误码、异常、分页、BaseEntity、纯静态工具。
 *
 * <p>声明为 OPEN 模块（code-organization.md 第 5.1 节）：其全部内容对所有模块开放，
 * 不强制走 api 命名接口。因为 common 就是给全模块复用的「公共词汇表」，没有「内部实现」一说。
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.hify.common;
