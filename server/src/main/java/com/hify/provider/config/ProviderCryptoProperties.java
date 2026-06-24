package com.hify.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 供应商加密配置（外化到 application.yml 的 {@code hify.provider.crypto.*}）。
 * 主密钥这类敏感项走 {@code .env} 的 HIFY_PROVIDER_MASTER_KEY 引用，不在代码/yml 写明文。
 * 由 {@link ProviderConfig} 上的 {@code @EnableConfigurationProperties} 注册绑定。
 */
@ConfigurationProperties(prefix = "hify.provider.crypto")
public class ProviderCryptoProperties {

    /** 加密主密钥（任意非空字符串）；ApiKeyCipher 用 SHA-256 派生为 32 字节 AES-256 密钥。 */
    private String masterKey;

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }
}
