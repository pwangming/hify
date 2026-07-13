package com.hify.conversation.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.conversation.dto.MessageToolCall;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * message.tool_calls（jsonb）↔ {@code List<MessageToolCall>}（照 MessageSourcesTypeHandler）。
 * 写出包 PGobject(type=jsonb)；读入空/空白兜底 List.of()，保证字段不为 null。
 * 实体需 @TableName(autoResultMap=true)（Message 已具备）。
 */
public class MessageToolCallsTypeHandler extends BaseTypeHandler<List<MessageToolCall>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<MessageToolCall>> LIST_TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<MessageToolCall> parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        try {
            obj.setValue(MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("序列化 message.tool_calls 失败", e);
        }
        ps.setObject(i, obj);
    }

    @Override
    public List<MessageToolCall> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<MessageToolCall> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<MessageToolCall> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<MessageToolCall> parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new SQLException("反序列化 message.tool_calls 失败", e);
        }
    }
}
