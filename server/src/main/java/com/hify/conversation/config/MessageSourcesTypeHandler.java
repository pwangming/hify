package com.hify.conversation.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.conversation.dto.MessageSource;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * message.sources（jsonb）↔ {@code List<MessageSource>} 的类型处理器（照 AppConfigTypeHandler 手法）。
 * 写出包成 PGobject(type=jsonb)，否则 PG 报「column is of type jsonb but expression is of type varchar」。
 * 读入空/空白兜底为 List.of()，保证字段不为 null。实体需 @TableName(autoResultMap=true) 才启用本处理器。
 */
public class MessageSourcesTypeHandler extends BaseTypeHandler<List<MessageSource>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<MessageSource>> LIST_TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<MessageSource> parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        try {
            obj.setValue(MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("序列化 message.sources 失败", e);
        }
        ps.setObject(i, obj);
    }

    @Override
    public List<MessageSource> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<MessageSource> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<MessageSource> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<MessageSource> parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new SQLException("反序列化 message.sources 失败", e);
        }
    }
}
