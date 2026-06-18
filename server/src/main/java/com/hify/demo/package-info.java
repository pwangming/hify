/**
 * demo —— 刻意长期保留的<b>学习参考模块</b>（reference，非业务模块）。
 *
 * <p>目的：以最小代价完整演示 Controller → Service → Mapper → Entity → DTO 一条链路，以及各项基建
 * （BaseEntity 自动填充、@Valid 校验、Result/PageResult 信封、逻辑删除）如何串起来。真实业务模块
 * （conversation/workflow 等）较复杂，本模块作为新人对照基准：always-green、被测试守护、完全符合规范。
 *
 * <p>定位与约束（详见 code-organization.md 第 1 节「学习参考模块」）：
 * <ul>
 *   <li>不属于 10 个业务模块，不承载任何业务能力；</li>
 *   <li>依赖白名单仅 common、infra；</li>
 *   <li><b>任何业务模块都禁止依赖 demo</b>——它是叶子，只能被读、不能被引用，由 ModularityTests 守护。</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "infra"}
)
package com.hify.demo;
