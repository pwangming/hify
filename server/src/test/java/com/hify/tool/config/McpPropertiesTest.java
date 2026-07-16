package com.hify.tool.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守 allowed-private-hosts 的绑定契约：application.yml 写的是
 * ${HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS:}，env 未配时值为空串——必须绑成空列表
 * （行为与 T4a 完全一致），逗号分隔绑成多条目。
 */
class McpPropertiesTest {

    private McpProperties bind(String value) {
        var source = new MapConfigurationPropertySource(
                Map.of("hify.tool.mcp.allowed-private-hosts", value));
        return new Binder(source)
                .bind("hify.tool.mcp", Bindable.ofInstance(new McpProperties())).get();
    }

    @Test
    void emptyString_bindsToEmptyList() {
        assertThat(bind("").getAllowedPrivateHosts()).isEmpty();
    }

    @Test
    void commaSeparated_bindsToEntries() {
        assertThat(bind("host.docker.internal,192.168.1.10").getAllowedPrivateHosts())
                .containsExactly("host.docker.internal", "192.168.1.10");
    }
}
