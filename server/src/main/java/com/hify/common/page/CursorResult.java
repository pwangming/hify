package com.hify.common.page;

import java.util.List;

/**
 * 游标分页结果（api-standards.md 第 3.1 节），用于消息流、运行日志、对外 API 这类
 * 「只往后翻、不需要总页数」的场景。<b>不返回 total</b>（大表 count 代价高）。
 *
 * <p>对应的 JSON：{@code { "list": [...], "nextCursor": "MTcxOC4uLg", "hasMore": true }}。
 *
 * @param list       当前批数据；<b>永不为 null</b>，空就是空列表
 * @param nextCursor 下一页游标：排序键（create_time + id）的 Base64 编码，<b>对客户端不透明</b>，
 *                   客户端只能原样回传、禁止自行构造；{@code hasMore=false} 时为 null
 * @param hasMore    是否还有下一页
 */
public record CursorResult<T>(List<T> list, String nextCursor, boolean hasMore) {

    /** 工厂方法：list 兜底空列表；并强制「没有下一页时 nextCursor 必须为 null」。 */
    public static <T> CursorResult<T> of(List<T> list, String nextCursor, boolean hasMore) {
        return new CursorResult<>(list == null ? List.of() : list, hasMore ? nextCursor : null, hasMore);
    }
}
