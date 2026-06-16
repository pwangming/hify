/**
 * usage 模块对外公开契约：其他模块只能 import 本包（{@code com.hify.usage.api..}）下的内容。
 * 典型成员：UsageFacade.checkQuota(...)、TokenUsedEvent / ToolInvokedEvent。
 */
@org.springframework.modulith.NamedInterface("api")
package com.hify.usage.api;
