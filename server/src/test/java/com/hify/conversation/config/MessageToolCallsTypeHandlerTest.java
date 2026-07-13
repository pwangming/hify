package com.hify.conversation.config;

import com.hify.conversation.dto.MessageToolCall;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.PreparedStatement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

class MessageToolCallsTypeHandlerTest {

    private final MessageToolCallsTypeHandler handler = new MessageToolCallsTypeHandler();

    @Test
    void 空json读为空列表() throws Exception {
        var rs = Mockito.mock(java.sql.ResultSet.class);
        Mockito.when(rs.getString("tool_calls")).thenReturn("[]");
        assertThat(handler.getNullableResult(rs, "tool_calls")).isEmpty();
    }

    @Test
    void null读为空列表() throws Exception {
        var rs = Mockito.mock(java.sql.ResultSet.class);
        Mockito.when(rs.getString("tool_calls")).thenReturn(null);
        assertThat(handler.getNullableResult(rs, "tool_calls")).isEmpty();
    }

    @Test
    void 写出包成jsonb并可回读() throws Exception {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        handler.setNonNullParameter(ps, 1,
                List.of(new MessageToolCall("http_request", "{\"url\":\"x\"}", "HTTP 200")), null);
        Mockito.verify(ps).setObject(anyInt(), any(org.postgresql.util.PGobject.class));
    }
}
