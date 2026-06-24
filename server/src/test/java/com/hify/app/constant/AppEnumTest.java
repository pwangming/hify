package com.hify.app.constant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppEnumTest {
    @Test
    void 类型与状态枚举值与DB约束一致() {
        assertEquals("chat", AppType.CHAT.value());
        assertEquals("workflow", AppType.WORKFLOW.value());
        assertEquals("enabled", AppStatus.ENABLED.value());
        assertEquals("disabled", AppStatus.DISABLED.value());
    }

    @Test
    void 类型不支持错误码为16001且400() {
        assertEquals(16001, AppError.APP_TYPE_NOT_SUPPORTED.code());
        assertEquals(HttpStatus.BAD_REQUEST, AppError.APP_TYPE_NOT_SUPPORTED.status());
    }
}
