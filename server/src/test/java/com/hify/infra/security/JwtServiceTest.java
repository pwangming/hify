package com.hify.infra.security;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link JwtService} 纯单元测试：发票/验票往返、过期、篡改、换密钥四种情形，不启动 Spring。
 */
class JwtServiceTest {

    /** 测试用密钥必须 ≥ 32 字节，否则 jjwt 在构造时就拒绝。 */
    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    private JwtService newService(long expireMinutes) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setExpireMinutes(expireMinutes);
        return new JwtService(properties);
    }

    @Test
    void 发票再验票_往返字段一致() {
        JwtService service = newService(60);
        String token = service.generateToken(new CurrentUser(42L, "alice", CurrentUser.ROLE_ADMIN));

        CurrentUser parsed = service.parseToken(token);

        assertEquals(42L, parsed.userId());
        assertEquals("alice", parsed.username());
        assertEquals(CurrentUser.ROLE_ADMIN, parsed.role());
    }

    @Test
    void 过期令牌_抛TOKEN_EXPIRED_10003() {
        JwtService service = newService(-1); // 有效期 -1 分钟 = 签出来即过期
        String token = service.generateToken(new CurrentUser(1L, "bob", CurrentUser.ROLE_MEMBER));

        BizException ex = assertThrows(BizException.class, () -> service.parseToken(token));
        assertEquals(CommonError.TOKEN_EXPIRED, ex.errorCode());
    }

    @Test
    void 篡改令牌_抛UNAUTHORIZED_10002() {
        JwtService service = newService(60);
        String token = service.generateToken(new CurrentUser(1L, "bob", CurrentUser.ROLE_MEMBER));

        // 篡改 payload 段（第一个 '.' 之后）的首字符——被签名保护的内容一变，签名即对不上。
        // 不能改"签名段最后一个字符"：Base64URL 末字符有 2 个未使用比特，部分取值会解码成相同
        // 字节、校验仍通过，会让用例偶发失败（确定性测试不能依赖这种运气）。
        char[] chars = token.toCharArray();
        int payloadFirst = token.indexOf('.') + 1;
        chars[payloadFirst] = (chars[payloadFirst] == 'A') ? 'B' : 'A';
        String tampered = new String(chars);

        BizException ex = assertThrows(BizException.class, () -> service.parseToken(tampered));
        assertEquals(CommonError.UNAUTHORIZED, ex.errorCode());
    }

    @Test
    void 用别的密钥签的票_抛UNAUTHORIZED_10002() {
        String token = newService(60).generateToken(new CurrentUser(1L, "bob", CurrentUser.ROLE_MEMBER));

        JwtProperties otherKey = new JwtProperties();
        otherKey.setSecret("another-secret-another-secret-another-9876543210");
        JwtService verifier = new JwtService(otherKey);

        BizException ex = assertThrows(BizException.class, () -> verifier.parseToken(token));
        assertEquals(CommonError.UNAUTHORIZED, ex.errorCode());
    }
}
