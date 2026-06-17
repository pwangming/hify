package com.hify.infra.security;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 的发票与验票（jjwt 0.12 API）。是系统里唯一签发/解析令牌的地方。
 *
 * <ul>
 *   <li>{@link #generateToken} —— 登录成功后由 identity 模块调用签发令牌（本块尚无登录接口，
 *       测试与开发期可直接调它造票）；</li>
 *   <li>{@link #parseToken} —— 由 {@link JwtAuthenticationFilter} 每个请求调用验票。</li>
 * </ul>
 *
 * <p>载荷约定：subject = userId，自定义 claim {@code username} / {@code role}。
 * 验票失败按原因区分错误码：过期 → 10003（前端据此重新登录），其余无效 → 10002。
 */
@Component
public class JwtService {

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final long expireSeconds;

    public JwtService(JwtProperties properties) {
        // hmacShaKeyFor 要求 ≥ 32 字节；不足会在这里启动即失败，把"密钥太短"暴露在启动期而非运行期。
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = properties.getExpireMinutes() * 60;
    }

    /** 签发令牌。 */
    public String generateToken(CurrentUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.userId()))
                .claim(CLAIM_USERNAME, user.username())
                .claim(CLAIM_ROLE, user.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * 验票并解析出当前用户。
     *
     * @throws BizException 10003（已过期）或 10002（签名不符/格式非法等一切其他无效情形）
     */
    public CurrentUser parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new CurrentUser(
                    Long.valueOf(claims.getSubject()),
                    claims.get(CLAIM_USERNAME, String.class),
                    claims.get(CLAIM_ROLE, String.class));
        } catch (ExpiredJwtException ex) {
            throw new BizException(CommonError.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException ex) {
            // 签名不符、结构损坏、subject 非数字等：一律按"凭证无效"，不向客户端泄露具体原因。
            throw new BizException(CommonError.UNAUTHORIZED);
        }
    }
}
