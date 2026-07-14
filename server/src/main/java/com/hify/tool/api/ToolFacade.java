package com.hify.tool.api;

import org.springframework.ai.tool.ToolCallback;

import java.util.Collection;
import java.util.List;

/**
 * tool 模块对外门面。
 * 例外：本模块 Facade 允许在签名中使用 Spring AI 类型（ToolCallback）——把 provider 的同类例外扩展到 tool
 * （拍板结论，见 code-organization.md §2「api/」例外条目）。工具对 Agent 透明的天然载体即 ToolCallback。
 */
public interface ToolFacade {

    /** 取全部 enabled 的内置工具 ToolCallback（T1：HTTP + 代码）。 */
    List<ToolCallback> getBuiltinToolCallbacks();

    /** 取指定 id 的 enabled 工具 ToolCallback（per-app 选择）。未知/停用 id 跳过；空集合→空列表。 */
    List<ToolCallback> getToolCallbacks(Collection<Long> toolIds);

    /** 校验勾选的工具 id 都「现存且 enabled」，否则抛 PARAM_INVALID。空集合直接通过。 */
    void validateToolIds(Collection<Long> toolIds);
}
