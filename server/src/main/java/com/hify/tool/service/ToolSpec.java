package com.hify.tool.service;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.hify.tool.service.openapi.OpenApiToolSpec;

/**
 * tool.spec(jsonb) 的多态载体：openapi 与 mcp 两种形状共用一列，靠 kind 分派。
 *
 * <p>不用 sealed：无 JPMS 时 sealed 的实现类必须与接口同包，而两个实现分处 service/openapi 与
 * service/mcp 子包，挪包会牵动 T3a 一堆 import，收益不抵成本（T4a spec 决策 7）。
 *
 * <p>defaultImpl：T3a 时期落库的 openapi 行 jsonb 里没有 kind，缺 kind 时按 openapi 解。
 * V25 迁移已给存量补齐，此处是兜底（双保险，别删）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind",
        defaultImpl = OpenApiToolSpec.class)
@JsonSubTypes({
        @JsonSubTypes.Type(value = OpenApiToolSpec.class, name = "openapi")
})
public interface ToolSpec {}
