package com.hify.common.page;

import java.util.List;

/**
 * 页码分页结果（api-standards.md 第 3.1 节），用于管理后台列表这类「要显示总页数」的场景。
 *
 * <p>对应的 JSON：{@code { "list": [...], "total": 134, "page": 1, "size": 20 }}。
 *
 * <p>另一种分页是 {@link CursorResult}（游标分页，用于消息流/运行日志/对外 API，不返回 total）。
 * 两者刻意分开，不要混用。
 *
 * @param list  当前页数据；<b>永不为 null</b>，空就是空列表（api-standards.md 第 4 节）
 * @param total 总条数；高水位表关闭 count 时返回 -1，前端则不显示总页数
 * @param page  当前页码，从 1 起
 * @param size  每页条数
 */
public record PageResult<T>(List<T> list, long total, long page, long size) {

    /** 工厂方法：把可能为 null 的 list 兜底成空列表，保证「集合永不为 null」。 */
    public static <T> PageResult<T> of(List<T> list, long total, long page, long size) {
        return new PageResult<>(list == null ? List.of() : list, total, page, size);
    }
}
