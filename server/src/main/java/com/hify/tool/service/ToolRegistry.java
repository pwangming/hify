package com.hify.tool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.builtin.BuiltinTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具注册表：读 DB 中 enabled 的工具行，对 source=builtin 的行按 name 绑定内置执行器，
 * 产出 Spring AI ToolCallback 列表。ToolDefinition 的 name/description 取自 DB 行（统一来源，
 * 与 T3/T4 openapi/mcp 一致），inputSchema 取自 builtin 执行器（builtin 特有，代码为准）。
 * T1 只处理 builtin；openapi/mcp 行 T3/T4 再补分支（本轮无此类行）。
 */
@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ToolMapper toolMapper;
    private final Map<String, BuiltinTool> builtinByName;

    public ToolRegistry(ToolMapper toolMapper, List<BuiltinTool> builtinTools) {
        this.toolMapper = toolMapper;
        this.builtinByName = builtinTools.stream()
                .collect(Collectors.toMap(BuiltinTool::name, Function.identity()));
    }

    /** 全部 enabled 的内置工具 → ToolCallback（找不到执行器的行跳过并告警）。 */
    public List<ToolCallback> getBuiltinToolCallbacks() {
        List<Tool> rows = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .eq(Tool::getSource, "builtin")
                .eq(Tool::getEnabled, true)
                .orderByAsc(Tool::getName));
        return buildCallbacks(rows);
    }

    /** 按 id 取 enabled 的 builtin 工具 ToolCallback（未知/停用/无执行器的跳过）。空集合→空列表。 */
    public List<ToolCallback> getToolCallbacks(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Tool> rows = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .eq(Tool::getSource, "builtin")
                .eq(Tool::getEnabled, true)
                .in(Tool::getId, ids)
                .orderByAsc(Tool::getName));
        return buildCallbacks(rows);
    }

    /** 给定 id 集合，返回其中「现存且 enabled」的 id（用于绑定前校验）。空集合→空集。 */
    public Set<Long> filterEnabledIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        return toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                        .eq(Tool::getEnabled, true)
                        .in(Tool::getId, ids))
                .stream().map(Tool::getId).collect(Collectors.toSet());
    }

    private List<ToolCallback> buildCallbacks(List<Tool> rows) {
        List<ToolCallback> callbacks = new ArrayList<>(rows.size());
        for (Tool row : rows) {
            BuiltinTool exec = builtinByName.get(row.getName());
            if (exec == null) {
                log.warn("内置工具行无对应执行器，跳过 name={}", row.getName());
                continue;
            }
            ToolDefinition def = DefaultToolDefinition.builder()
                    .name(row.getName())
                    .description(row.getDescription())
                    .inputSchema(exec.inputSchema())
                    .build();
            callbacks.add(new BuiltinToolCallback(def, exec));
        }
        return callbacks;
    }
}
