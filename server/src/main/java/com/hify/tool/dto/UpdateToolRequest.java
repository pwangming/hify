package com.hify.tool.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 全量更新：名称、描述、spec 来源（openapi 文档 / mcp 地址）、鉴权头一起替换。type 缺省 = openapi。 */
public record UpdateToolRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 500) String description,
        String type,
        String specText,
        String url,
        String transport,
        @Valid List<AuthHeaderInput> authHeaders) {

    public String typeOrDefault() {
        return type == null || type.isBlank() ? CreateToolRequest.TYPE_OPENAPI : type;
    }

    @AssertTrue(message = "OpenAPI 工具须提供 specText；MCP 工具须提供 url")
    public boolean isPayloadValid() {
        return CreateToolRequest.TYPE_MCP.equals(typeOrDefault())
                ? url != null && !url.isBlank()
                : specText != null && !specText.isBlank();
    }

    @AssertTrue(message = "type 只能是 openapi 或 mcp")
    public boolean isTypeValid() {
        return CreateToolRequest.TYPE_OPENAPI.equals(typeOrDefault())
                || CreateToolRequest.TYPE_MCP.equals(typeOrDefault());
    }
}
