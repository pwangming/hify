package com.hify.provider.dto;

import java.time.OffsetDateTime;

/**
 * 供应商出参。刻意<b>不含</b>明文/密文 key——只回 {@code apiKeyTail}（明文后 4 位）供前端掩码展示。
 * id 经 infra Jackson 全局序列化为字符串；createTime 为 ISO-8601 带时区。
 * 本 record 不依赖 entity（ArchUnit 禁 DTO import entity），「实体→视图」投影在 ProviderService 完成。
 */
public record ProviderResponse(
        Long id,
        String name,
        String protocol,
        String baseUrl,
        String status,
        String apiKeyTail,
        OffsetDateTime createTime) {
}
