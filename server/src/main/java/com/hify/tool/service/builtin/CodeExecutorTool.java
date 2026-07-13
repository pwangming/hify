package com.hify.tool.service.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.infra.outbound.SandboxClient;
import com.hify.infra.outbound.SandboxResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 内置代码工具：复用 infra 的 SandboxClient（隔离容器/双超时/并发信号量/输出上限）。
 * Agent 直接把值写进代码，inputs 传空 map（沙箱契约：用户码定义 main(**inputs) 返回 dict，此处 main()）。
 * 任何失败以错误文本返回给模型，不抛（不中断 Agent 循环）。
 */
@Component
public class CodeExecutorTool implements BuiltinTool {

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "code":{"type":"string","description":"完整 Python 源码；必须定义无参函数 main() 并返回一个 dict"}},
              "required":["code"]}""";

    private final SandboxClient sandbox;
    private final ObjectMapper objectMapper;

    public CodeExecutorTool(SandboxClient sandbox, ObjectMapper objectMapper) {
        this.sandbox = sandbox;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "code_executor";
    }

    @Override
    public String inputSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(String argsJson) {
        String code;
        try {
            JsonNode args = objectMapper.readTree(argsJson);
            code = args.path("code").asText("");
        } catch (Exception e) {
            return "错误：参数不是合法 JSON：" + e.getMessage();
        }
        if (code.isBlank()) {
            return "错误：缺少 code 参数";
        }
        try {
            SandboxResult r = sandbox.run(code, Map.of());
            if (!r.ok()) {
                return "错误：" + r.error();
            }
            return objectMapper.writeValueAsString(r.outputs());
        } catch (BizException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            return "错误：结果序列化失败：" + e.getMessage();
        }
    }
}
