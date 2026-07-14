package com.hify.tool.dto;

import java.time.OffsetDateTime;

/** admin 列表/写返回。operationCount：openapi=操作数，builtin=null。 */
public record ToolAdminResponse(
        Long id, String name, String description, String source, boolean enabled,
        Integer operationCount, Long ownerId, OffsetDateTime createTime, OffsetDateTime updateTime) {}
