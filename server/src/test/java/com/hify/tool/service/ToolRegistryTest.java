package com.hify.tool.service;

import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.builtin.BuiltinTool;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ToolRegistryTest {

    private final ToolMapper toolMapper = Mockito.mock(ToolMapper.class);

    private static BuiltinTool fake(String name, String result) {
        return new BuiltinTool() {
            public String name() { return name; }
            public String inputSchema() { return "{\"type\":\"object\"}"; }
            public String execute(String argsJson) { return result; }
        };
    }

    private static Tool row(String name, String desc, boolean enabled) {
        Tool t = new Tool();
        t.setName(name); t.setDescription(desc); t.setSource("builtin"); t.setEnabled(enabled);
        return t;
    }

    @Test
    void 由builtin行绑定执行器产出callback() {
        when(toolMapper.selectList(any())).thenReturn(List.of(
                row("http_request", "HTTP 工具说明", true)));
        ToolRegistry registry = new ToolRegistry(toolMapper,
                List.of(fake("http_request", "OK"), fake("code_executor", "X")));

        List<ToolCallback> cbs = registry.getBuiltinToolCallbacks();

        assertThat(cbs).hasSize(1);
        ToolCallback cb = cbs.get(0);
        assertThat(cb.getToolDefinition().name()).isEqualTo("http_request");
        assertThat(cb.getToolDefinition().description()).isEqualTo("HTTP 工具说明");
        assertThat(cb.call("{}")).isEqualTo("OK");
    }

    @Test
    void 行无对应执行器则跳过_不抛() {
        when(toolMapper.selectList(any())).thenReturn(List.of(row("ghost", "无执行器", true)));
        ToolRegistry registry = new ToolRegistry(toolMapper, List.of(fake("http_request", "OK")));
        assertThat(registry.getBuiltinToolCallbacks()).isEmpty();
    }
}
