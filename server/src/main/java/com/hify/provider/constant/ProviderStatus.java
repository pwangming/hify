package com.hify.provider.constant;

/** 供应商管理态。存库为小写字符串（与 model_provider.status 的 check 约束一致），镜像 UserStatus。 */
public enum ProviderStatus {

    ENABLED("enabled"),
    DISABLED("disabled");

    private final String value;

    ProviderStatus(String value) {
        this.value = value;
    }

    /** 入库/比较用的字符串值。 */
    public String value() {
        return value;
    }
}
