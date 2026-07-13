package com.hify.tool.service;

import com.hify.tool.service.builtin.BuiltinTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 把一个 {@link BuiltinTool} 适配成 Spring AI {@link ToolCallback}。
 * 直接实现接口，避开 FunctionToolCallback 的泛型入参反序列化——execute 自己解析 JSON 参数字符串。
 */
public class BuiltinToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final BuiltinTool tool;

    public BuiltinToolCallback(ToolDefinition definition, BuiltinTool tool) {
        this.definition = definition;
        this.tool = tool;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return tool.execute(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }
}
