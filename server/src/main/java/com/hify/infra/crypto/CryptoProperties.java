package com.hify.infra.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对称加密主密钥配置。历史上归 provider，本轮提到 infra 供 provider 与 tool 共用；
 * 前缀保留 {@code hify.provider.crypto}（application.yml/.env 零改动，避免破坏已加密数据）。
 * 主密钥走 .env 的 HIFY_PROVIDER_MASTER_KEY，不在代码/yml 写明文。
 */
@Component
@ConfigurationProperties(prefix = "hify.provider.crypto")
public class CryptoProperties {

    /** 加密主密钥（任意非空字符串）；SecretCipher 用 SHA-256 派生为 32 字节 AES-256 密钥。 */
    private String masterKey;

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }
}
