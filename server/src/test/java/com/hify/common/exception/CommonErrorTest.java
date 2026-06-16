package com.hify.common.exception;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommonError} 的约束测试。
 *
 * <p>这类测试不验证「某个值等于几」，而是验证「整张枚举满足某条不变量」——
 * 防止以后有人新增错误码时手滑（码段写错、code 撞车、漏填提示）。
 */
class CommonErrorTest {

    @Test
    void 所有码都落在通用段10xxx() {
        for (CommonError e : CommonError.values()) {
            assertTrue(e.code() >= 10000 && e.code() <= 10999,
                    () -> e.name() + " 的 code=" + e.code() + " 超出通用段 10xxx");
        }
    }

    @Test
    void code不得重复() {
        Set<Integer> seen = new HashSet<>();
        for (CommonError e : CommonError.values()) {
            assertTrue(seen.add(e.code()), () -> "重复的 code: " + e.code());
        }
    }

    @Test
    void status与提示都不为空() {
        for (CommonError e : CommonError.values()) {
            assertNotNull(e.status(), () -> e.name() + " 缺少 HTTP 状态");
            assertNotNull(e.defaultMessage(), () -> e.name() + " 缺少默认提示");
        }
    }
}
