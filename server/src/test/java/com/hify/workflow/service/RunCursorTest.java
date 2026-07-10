package com.hify.workflow.service;

import com.hify.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunCursorTest {

    @Test
    void 编解码往返() {
        OffsetDateTime t = OffsetDateTime.parse("2026-07-09T10:30:00.123456+08:00");
        String cursor = RunCursor.encode(t, 42L);
        RunCursor.Cursor decoded = RunCursor.decode(cursor);
        assertEquals(t, decoded.createTime());
        assertEquals(42L, decoded.id());
    }

    @Test
    void 非法游标_报10001() {
        BizException ex = assertThrows(BizException.class, () -> RunCursor.decode("garbage!!"));
        assertEquals(10001, ex.errorCode().code());
    }
}
