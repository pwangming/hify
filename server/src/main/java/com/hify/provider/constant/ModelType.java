package com.hify.provider.constant;

/** 模型用途。存库为小写字符串（与 ai_model.type 的 check 约束一致）。 */
public enum ModelType {

    CHAT("chat"),
    EMBEDDING("embedding");

    private final String value;

    ModelType(String value) {
        this.value = value;
    }

    /** 入库/比较用的字符串值。 */
    public String value() {
        return value;
    }
}
