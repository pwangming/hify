package com.hify.app.config;

import com.hify.app.api.dto.AppConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class AppConfigTypeHandlerTest {

    private final AppConfigTypeHandler handler = new AppConfigTypeHandler();

    @Test
    void 写出_序列化为jsonb的PGobject() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        handler.setNonNullParameter(ps, 1, new AppConfig("你是客服助手"), null);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ps).setObject(eq(1), captor.capture());
        PGobject obj = (PGobject) captor.getValue();
        assertEquals("jsonb", obj.getType());
        assertEquals("{\"systemPrompt\":\"你是客服助手\"}", obj.getValue());
    }

    @Test
    void 读入_从json反序列化() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("config")).thenReturn("{\"systemPrompt\":\"hi\"}");
        AppConfig cfg = handler.getNullableResult(rs, "config");
        assertEquals("hi", cfg.systemPrompt());
    }

    @Test
    void 读入_空值兜底为空配置() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("config")).thenReturn(null);
        AppConfig cfg = handler.getNullableResult(rs, "config");
        assertNull(cfg.systemPrompt());
    }
}
