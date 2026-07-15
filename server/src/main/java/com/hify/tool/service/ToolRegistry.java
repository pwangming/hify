package com.hify.tool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.infra.crypto.SecretCipher;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.builtin.BuiltinTool;
import com.hify.tool.service.openapi.OpenApiToolCallback;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具注册表：读 DB 中 enabled 的工具行，对 builtin 按 name 绑定内置执行器，对 openapi 行读时展开
 * 成多个 ToolCallback。ToolDefinition 的 name/description 取自 DB/spec 行，统一作为模型看到的工具定义。
 */
@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ToolMapper toolMapper;
    private final Map<String, BuiltinTool> builtinByName;
    private final SecretCipher secretCipher;
    private final OutboundHttpClient outboundHttpClient;
    private final ObjectMapper objectMapper;

    public ToolRegistry(ToolMapper toolMapper, List<BuiltinTool> builtinTools,
                        SecretCipher secretCipher, OutboundHttpClient outboundHttpClient,
                        ObjectMapper objectMapper) {
        this.toolMapper = toolMapper;
        this.builtinByName = builtinTools.stream()
                .collect(Collectors.toMap(BuiltinTool::name, Function.identity()));
        this.secretCipher = secretCipher;
        this.outboundHttpClient = outboundHttpClient;
        this.objectMapper = objectMapper;
    }

    /** 全部 enabled 的内置工具 → ToolCallback（找不到执行器的行跳过并告警）。 */
    public List<ToolCallback> getBuiltinToolCallbacks() {
        List<Tool> rows = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .eq(Tool::getSource, "builtin")
                .eq(Tool::getEnabled, true)
                .orderByAsc(Tool::getName));
        return buildCallbacks(rows);
    }

    /** 按 id 取 enabled 工具 ToolCallback（openapi 行会展开成多个回调）。空集合→空列表。 */
    public List<ToolCallback> getToolCallbacks(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Tool> rows = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
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
            if ("openapi".equals(row.getSource())) {
                callbacks.addAll(expandOpenApi(row));
                continue;
            }
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

    private List<ToolCallback> expandOpenApi(Tool row) {
        if (!(row.getSpec() instanceof OpenApiToolSpec spec) || spec.operations() == null) {
            log.warn("openapi 工具行 spec 为空，跳过 id={}", row.getId());
            return List.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (spec.authHeaders() != null) {
            for (OpenApiToolSpec.AuthHeader h : spec.authHeaders()) {
                headers.put(h.name(), secretCipher.decrypt(h.valueEnc()));
            }
        }
        String prefix = sanitizeName(row.getName());
        List<ToolCallback> out = new ArrayList<>(spec.operations().size());
        for (OpenApiToolSpec.Operation op : spec.operations()) {
            ToolDefinition def = DefaultToolDefinition.builder()
                    .name(prefix + "__" + op.opName())
                    .description(op.description())
                    .inputSchema(op.inputSchema())
                    .build();
            out.add(new OpenApiToolCallback(def, op, spec.baseUrl(), headers, outboundHttpClient, objectMapper));
        }
        return out;
    }

    private static String sanitizeName(String raw) {
        String s = raw.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_");
        return s.replaceAll("^_+", "").replaceAll("_+$", "");
    }
}
