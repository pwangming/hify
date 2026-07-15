package com.hify.tool.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hify.tool.service.ToolSpec;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * tool.spec(jsonb) ↔ {@link ToolSpec}（kind 分派 openapi/mcp）。builtin 行 spec 为 null（读空→null）。
 * 实体需 autoResultMap=true。
 *
 * <p>用私有 ObjectMapper 而非注入 Spring 那个：spec 是<b>库内存储</b>格式，不该被对外 JSON 的全局策略
 * （api-standards §4 的 JsonInclude.ALWAYS）牵着走——NON_NULL 让 jsonb 干净；JavaTimeModule 是
 * McpToolSpec.discoveredAt(OffsetDateTime) 的硬需求，不注册直接序列化失败。
 */
public class ToolSpecTypeHandler extends BaseTypeHandler<ToolSpec> {

    private static final ObjectMapper MAPPER = specMapper();

    /** 供测试与 mcp 侧 schema 序列化复用的同款 mapper。 */
    public static ObjectMapper specMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, ToolSpec parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        try {
            obj.setValue(MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("序列化 tool.spec 失败", e);
        }
        ps.setObject(i, obj);
    }

    @Override
    public ToolSpec getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public ToolSpec getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public ToolSpec getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private ToolSpec parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, ToolSpec.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("反序列化 tool.spec 失败", e);
        }
    }
}
