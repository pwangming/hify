package com.hify.app.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.app.api.dto.AppConfig;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * app.config（jsonb）↔ {@link AppConfig} 的类型处理器。
 * 写出包成 PGobject(type=jsonb)，否则 PG 报「column is of type jsonb but expression is of type varchar」。
 * 读入空值兜底为 new AppConfig(null)，保证字段不为 null。实体需 @TableName(autoResultMap=true) 才在查询时启用本处理器。
 */
public class AppConfigTypeHandler extends BaseTypeHandler<AppConfig> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, AppConfig parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        try {
            obj.setValue(MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("序列化 app.config 失败", e);
        }
        ps.setObject(i, obj);
    }

    @Override
    public AppConfig getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public AppConfig getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public AppConfig getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private AppConfig parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return new AppConfig(null);
        }
        try {
            return MAPPER.readValue(json, AppConfig.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("反序列化 app.config 失败", e);
        }
    }
}
