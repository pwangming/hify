package com.hify.common.page;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两种分页结果工厂方法的单元测试，重点验证两条不变量：
 * 「list 永不为 null」「hasMore=false 时 nextCursor 必为 null」。
 */
class PageResultTest {

    @Test
    void 页码分页_正常透传字段() {
        PageResult<String> page = PageResult.of(List.of("a", "b"), 134, 1, 20);

        assertEquals(List.of("a", "b"), page.list());
        assertEquals(134, page.total());
        assertEquals(1, page.page());
        assertEquals(20, page.size());
    }

    @Test
    void 页码分页_null列表兜底为空列表() {
        PageResult<String> page = PageResult.of(null, 0, 1, 20);

        assertNotNull(page.list());
        assertTrue(page.list().isEmpty());
    }

    @Test
    void 游标分页_有下一页时保留游标() {
        CursorResult<String> cursor = CursorResult.of(List.of("a"), "next-token", true);

        assertTrue(cursor.hasMore());
        assertEquals("next-token", cursor.nextCursor());
    }

    @Test
    void 游标分页_无下一页时游标强制为null() {
        // 即便误传了 nextCursor，hasMore=false 也应抹成 null
        CursorResult<String> cursor = CursorResult.of(List.of("a"), "should-be-ignored", false);

        assertFalse(cursor.hasMore());
        assertNull(cursor.nextCursor());
    }

    @Test
    void 游标分页_null列表兜底为空列表() {
        CursorResult<String> cursor = CursorResult.of(null, null, false);

        assertNotNull(cursor.list());
        assertTrue(cursor.list().isEmpty());
    }
}
