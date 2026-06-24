package com.hify.app.constant;

/** 应用类型，值与 app.type 的 check 约束一致（api-standards 序列化：枚举存小写字符串）。 */
public enum AppType {
    CHAT("chat"),
    WORKFLOW("workflow");

    private final String value;

    AppType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
