package com.hify.tool.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 注册自定义工具：openapi（粘贴文档）或 mcp（填服务器地址）+ 可选鉴权头。
 * type 缺省 = openapi——T3b 已上线的前端不传 type，不能弄坏它（T4a spec §5.2）。
 */
public record CreateToolRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 500) String description,
        String type,
        String specText,
        String url,
        String transport,
        @Valid List<AuthHeaderInput> authHeaders) {

    public static final String TYPE_OPENAPI = "openapi";
    public static final String TYPE_MCP = "mcp";

    /** 归一化：null/空 → openapi。 */
    public String typeOrDefault() {
        return type == null || type.isBlank() ? TYPE_OPENAPI : type;
    }

    @AssertTrue(message = "OpenAPI 工具须提供 specText；MCP 工具须提供 url")
    public boolean isPayloadValid() {
        return TYPE_MCP.equals(typeOrDefault())
                ? url != null && !url.isBlank()
                : specText != null && !specText.isBlank();
    }

    @AssertTrue(message = "type 只能是 openapi 或 mcp")
    public boolean isTypeValid() {
        return TYPE_OPENAPI.equals(typeOrDefault()) || TYPE_MCP.equals(typeOrDefault());
    }
}
