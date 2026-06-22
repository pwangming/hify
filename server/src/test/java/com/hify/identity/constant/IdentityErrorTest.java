package com.hify.identity.constant;

import com.hify.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IdentityError} 的约束测试：验证整张枚举满足不变量（落在 11xxx 段、code 不重复、
 * 状态与提示齐全），防止以后新增码时手滑。参照 CommonErrorTest 的写法。
 */
class IdentityErrorTest {

    @Test
    void 所有码都落在identity段11xxx() {
        for (IdentityError e : IdentityError.values()) {
            assertTrue(e.code() >= 11000 && e.code() <= 11999,
                    () -> e.name() + " 的 code=" + e.code() + " 超出 identity 段 11xxx");
        }
    }

    @Test
    void code不得重复() {
        Set<Integer> seen = new HashSet<>();
        for (IdentityError e : IdentityError.values()) {
            assertTrue(seen.add(e.code()), () -> "重复的 code: " + e.code());
        }
    }

    @Test
    void status与提示都不为空() {
        for (IdentityError e : IdentityError.values()) {
            ErrorCode ec = e;
            assertNotNull(ec.status(), () -> e.name() + " 缺少 HTTP 状态");
            assertNotNull(ec.defaultMessage(), () -> e.name() + " 缺少默认提示");
        }
    }
}
