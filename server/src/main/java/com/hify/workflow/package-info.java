/**
 * workflow —— 画布定义、节点执行引擎、运行日志。
 *
 * <p>依赖白名单（code-organization.md 第 1 节）：conversation、app、provider、knowledge、tool、usage
 * + common、infra。配额检查只在本模块「工作流触发」入口做。
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "conversation::api", "app::api", "provider::api", "knowledge::api",
                "tool::api", "usage::api", "common", "infra"
        }
)
package com.hify.workflow;
