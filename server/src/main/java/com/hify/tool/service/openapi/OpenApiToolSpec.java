package com.hify.tool.service.openapi;

import java.util.List;

/** tool.spec(jsonb) 映射：一条 openapi 注册的自包含执行描述。凭据只存密文 valueEnc。 */
public record OpenApiToolSpec(
        String baseUrl,
        List<AuthHeader> authHeaders,
        List<Operation> operations,
        String rawSpec) {

    public record AuthHeader(String name, String valueEnc) {}

    public record Operation(
            String opName,
            String method,
            String pathTemplate,
            String description,
            String inputSchema,
            List<Param> parameters) {}

    public record Param(String name, String in, boolean required) {}
}
