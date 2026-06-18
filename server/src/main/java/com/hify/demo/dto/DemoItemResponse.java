package com.hify.demo.dto;

import java.time.OffsetDateTime;

/**
 * DemoItem 的出参。{@code id} 会被 Jackson 全局序列化为字符串（防 JS 精度丢失），
 * 时间字段为带时区的 ISO-8601（如 {@code 2026-06-12T10:30:00+08:00}）。
 *
 * <p>Entity → Response 的转换写在 service 层（code-organization.md 第 2 节），
 * 故本 record 不依赖 entity，协议层与数据层解耦。
 */
public record DemoItemResponse(
        Long id,
        String name,
        Integer status,
        OffsetDateTime createTime,
        OffsetDateTime updateTime) {
}
