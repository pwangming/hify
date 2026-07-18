package com.hify.usage.service;

import com.hify.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogCursorTest {

    @Test
    void 编解码往返一致() {
        OffsetDateTime t = OffsetDateTime.parse("2026-07-17T10:30:00+08:00");
        String encoded = LogCursor.encode(t, 42L);
        LogCursor.Cursor decoded = LogCursor.decode(encoded);
        assertThat(decoded.createTime()).isEqualTo(t);
        assertThat(decoded.id()).isEqualTo(42L);
    }

    @Test
    void 非法游标抛10001() {
        assertThatThrownBy(() -> LogCursor.decode("不是游标")).isInstanceOf(BizException.class);
    }
}
