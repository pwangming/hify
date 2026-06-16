package com.hify;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * 模块边界守护测试（code-organization.md 第 5.2 节）。
 *
 * <p>{@link ApplicationModules#of} 会扫描 {@code com.hify} 下的一级包，把每个识别为一个模块，
 * 并读取各模块 {@code package-info.java} 上的 {@code @ApplicationModule(allowedDependencies=...)}。
 * {@link ApplicationModules#verify()} 一次性校验三件事：
 * <ul>
 *   <li>每个模块只依赖了白名单里声明的模块；</li>
 *   <li>跨模块访问只走对方的 {@code @NamedInterface("api")} 暴露面，没碰内部包；</li>
 *   <li>模块依赖图无循环（必须是 DAG）。</li>
 * </ul>
 *
 * <p>骨架阶段各模块基本是空的，verify() 会顺利通过——这正是「先把护栏立好」的意义：
 * 等后续往模块里写代码，一旦出现越界 import，这个测试立刻变红。
 */
class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(HifyApplication.class);

    @Test
    void 模块依赖白名单与无循环() {
        modules.verify();
    }
}
