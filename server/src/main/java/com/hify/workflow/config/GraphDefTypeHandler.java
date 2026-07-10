package com.hify.workflow.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.workflow.dto.GraphDef;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * workflow_def.graph（jsonb）↔ {@link GraphDef}。写出包成 PGobject(type=jsonb)；
 * 读入未知字段忽略（客户端可比服务端新）。实体需 @TableName(autoResultMap=true)。
 */
public class GraphDefTypeHandler extends BaseTypeHandler<GraphDef> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, GraphDef parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        try {
            obj.setValue(MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("序列化 workflow_def.graph 失败", e);
        }
        ps.setObject(i, obj);
    }

    @Override
    public GraphDef getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public GraphDef getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public GraphDef getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private GraphDef parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, GraphDef.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("反序列化 workflow_def.graph 失败", e);
        }
    }
}
