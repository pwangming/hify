package com.hify.tool.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.tool.service.openapi.OpenApiToolSpec;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** tool.spec(jsonb) ↔ {@link OpenApiToolSpec}。builtin 行 spec 为 null（读空→null）。实体需 autoResultMap=true。 */
public class OpenApiToolSpecTypeHandler extends BaseTypeHandler<OpenApiToolSpec> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, OpenApiToolSpec parameter, JdbcType jdbcType)
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
    public OpenApiToolSpec getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public OpenApiToolSpec getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public OpenApiToolSpec getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private OpenApiToolSpec parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, OpenApiToolSpec.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("反序列化 tool.spec 失败", e);
        }
    }
}
