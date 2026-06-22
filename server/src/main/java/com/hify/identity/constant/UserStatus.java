package com.hify.identity.constant;

/**
 * 用户状态。存库为小写字符串（与 sys_user.status 的 check 约束一致），
 * 用枚举集中两个取值、避免散落的魔法字符串。
 */
public enum UserStatus {

    ENABLED("enabled"),
    DISABLED("disabled");

    private final String value;

    UserStatus(String value) {
        this.value = value;
    }

    /** 入库/比较用的字符串值。 */
    public String value() {
        return value;
    }
}
