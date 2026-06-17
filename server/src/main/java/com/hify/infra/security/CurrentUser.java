package com.hify.infra.security;

/**
 * 当前登录用户——贯穿全系统的"当前用户"标准载体（code-organization.md 第 1 节：
 * 业务模块"当前用户"统一从 infra 获取，禁止依赖 identity 模块）。
 *
 * <p>它是 JWT 解析后的结果：{@link JwtService#parseToken} 从令牌里取出 userId / username / role
 * 组装成本对象，由 {@link JwtAuthenticationFilter} 放进安全上下文，业务代码再用
 * {@link CurrentUserHolder#current()} 取回。
 *
 * <p>{@code record} = 不可变、只装数据不带行为，契合"上下文里被并发共享"的定位。
 *
 * @param userId   用户自增 id（站内用；对外接口不暴露，见 api-standards.md 第 4 节）
 * @param username 用户名
 * @param role     角色，取值 {@link #ROLE_ADMIN} / {@link #ROLE_MEMBER}（小写，与 DB 枚举值一致）
 */
public record CurrentUser(Long userId, String username, String role) {

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_MEMBER = "member";

    /** 是否管理员。资源 owner 校验等场景会用到（api-standards.md 第 6 节：owner 或 Admin 才能改删）。 */
    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
