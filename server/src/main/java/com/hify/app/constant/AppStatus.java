package com.hify.app.constant;

/** 应用启停状态，值与 app.status 的 check 约束一致。 */
public enum AppStatus {
    ENABLED("enabled"),
    DISABLED("disabled");

    private final String value;

    AppStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
