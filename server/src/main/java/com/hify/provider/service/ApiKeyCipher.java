package com.hify.provider.service;

import com.hify.provider.config.ProviderCryptoProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 供应商 API Key 的对称加解密（AES-256-GCM）。主密钥来自 {@link ProviderCryptoProperties}
 * （经 .env 注入），用 SHA-256 派生出固定 32 字节 AES-256 密钥——无需主密钥本身恰好 32 字节。
 *
 * <p>密文格式 {@code base64(IV ‖ ciphertext ‖ GCM tag)}，每次加密随机 12 字节 IV。
 *
 * <p>本轮业务流程<b>只调 {@link #encrypt}</b>；{@link #decrypt} 已实现且有测试覆盖，但无任何
 * endpoint/service 流程触达（留到 C 轮 ResilientChatModel 调真实模型时接入），解密路径不可经接口到达。
 */
@Component
public class ApiKeyCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;        // GCM 推荐 12 字节
    private static final int TAG_LENGTH_BITS = 128; // GCM 认证标签 128 位

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyCipher(ProviderCryptoProperties properties) {
        this.key = deriveKey(properties.getMasterKey());
    }

    /** 明文 → {@code base64(IV ‖ 密文 ‖ tag)}。 */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    /** {@code base64(IV ‖ 密文 ‖ tag)} → 明文。本轮无业务调用方。 */
    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            byte[] ct = new byte[all.length - IV_LENGTH];
            System.arraycopy(all, IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 解密失败", e);
        }
    }

    /** SHA-256(masterKey) → 32 字节 → AES-256 密钥。 */
    private static SecretKeySpec deriveKey(String masterKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(masterKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("派生 AES 密钥失败", e);
        }
    }
}
