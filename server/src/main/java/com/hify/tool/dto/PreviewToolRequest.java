package com.hify.tool.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;

import java.util.List;

/** 预览：openapi 解析文档 / mcp 试连接并列工具，都只看不落库。type 缺省 = openapi。 */
public record PreviewToolRequest(
        String type,
        String specText,
        String url,
        String transport,
        @Valid List<AuthHeaderInput> authHeaders) {

    public String typeOrDefault() {
        return type == null || type.isBlank() ? CreateToolRequest.TYPE_OPENAPI : type;
    }

    @AssertTrue(message = "OpenAPI 预览须提供 specText；MCP 预览须提供 url")
    public boolean isPayloadValid() {
        return CreateToolRequest.TYPE_MCP.equals(typeOrDefault())
                ? url != null && !url.isBlank()
                : specText != null && !specText.isBlank();
    }
}
