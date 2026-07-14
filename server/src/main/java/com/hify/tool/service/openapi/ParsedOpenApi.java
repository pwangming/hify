package com.hify.tool.service.openapi;

import java.util.List;

/** OpenApiSpecParser 解析结果（尚不含鉴权头，鉴权由 admin 请求单独提供）。 */
public record ParsedOpenApi(String baseUrl, List<OpenApiToolSpec.Operation> operations) {}
