package com.hify.tool.dto;

import java.util.List;

/** admin 详情（供 T3b 编辑表单）。authHeaderNames 只回头名，绝不回明文值。 */
public record ToolAdminDetailResponse(
        Long id, String name, String description, String source, boolean enabled,
        String baseUrl, List<OperationView> operations, List<String> authHeaderNames, String rawSpec) {}
