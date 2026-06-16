/**
 * conversation —— 会话、消息、多轮记忆、Agent 编排。
 *
 * <p>依赖白名单（code-organization.md 第 1 节）：app、provider、knowledge、tool、usage + common、infra。
 * 配额检查（UsageFacade.checkQuota）只在本模块「收到消息」入口做。
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "app::api", "provider::api", "knowledge::api",
                "tool::api", "usage::api", "common", "infra"
        }
)
package com.hify.conversation;
