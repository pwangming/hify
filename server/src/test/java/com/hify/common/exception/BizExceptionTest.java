package com.hify.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link BizException} 三个构造器的单元测试。
 */
class BizExceptionTest {

    @Test
    void 仅错误码_message取默认提示() {
        BizException ex = new BizException(CommonError.FORBIDDEN);

        assertEquals("权限不足", ex.getMessage());
        assertSame(CommonError.FORBIDDEN, ex.errorCode());
    }

    @Test
    void 自定义message覆盖默认提示() {
        BizException ex = new BizException(CommonError.CONFLICT, "应用名已存在");

        assertEquals("应用名已存在", ex.getMessage());
        assertSame(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 带cause_保留原始异常() {
        // 转译底层异常时要保留 cause，排障堆栈才不丢（coding-standards.md 第 8 条）
        Throwable root = new IllegalStateException("底层炸了");

        BizException ex = new BizException(CommonError.INTERNAL_ERROR, "处理失败", root);

        assertSame(root, ex.getCause());
        assertSame(CommonError.INTERNAL_ERROR, ex.errorCode());
    }
}
