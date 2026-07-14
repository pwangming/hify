package com.hify.infra.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SecretCipher 单元测试：AES-256-GCM 往返一致、随机 IV、换密钥无法解密。不连库不依赖 Spring。 */
class SecretCipherTest {

    private SecretCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = new SecretCipher(props("unit-test-master-key-any-string"));
    }

    private CryptoProperties props(String masterKey) {
        CryptoProperties p = new CryptoProperties();
        p.setMasterKey(masterKey);
        return p;
    }

    @Test
    void 加密后能原样解密回来() {
        String plain = "sk-1234567890abcdef";
        String enc = cipher.encrypt(plain);
        assertNotEquals(plain, enc);
        assertEquals(plain, cipher.decrypt(enc));
    }

    @Test
    void 同一明文两次加密密文不同_因随机IV() {
        assertNotEquals(cipher.encrypt("sk-same-input"), cipher.encrypt("sk-same-input"));
    }

    @Test
    void 换主密钥无法解密() {
        String enc = cipher.encrypt("sk-secret");
        SecretCipher other = new SecretCipher(props("a-completely-different-key"));
        assertThrows(Exception.class, () -> other.decrypt(enc));
    }
}
