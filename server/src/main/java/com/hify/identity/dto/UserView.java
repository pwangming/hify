package com.hify.identity.dto;

import java.time.OffsetDateTime;

/**
 * admin 用户管理的统一响应视图。仅本模块用，禁止被其他模块 import。
 *
 * <p>刻意<b>不含</b> passwordHash——密码哈希绝不出响应。id 为 Long，经 infra Jackson 全局配置
 * 序列化为 JSON 字符串；createTime 为 ISO-8601 带时区。字段均不加局部序列化注解（全局兜底）。
 *
 * <p>本类不依赖 {@code entity}（LayerRulesTest「协议层不碰数据访问」禁止 dto 包持有 entity 依赖）；
 * 「实体→视图」的投影逻辑放在 {@link com.hify.identity.service.AdminUserService} 里完成。
 */
public record UserView(Long id, String username, String role, String status, OffsetDateTime createTime) {
}
