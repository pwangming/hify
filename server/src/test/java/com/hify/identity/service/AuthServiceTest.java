package com.hify.identity.service;

import com.hify.common.exception.BizException;
import com.hify.identity.constant.IdentityError;
import com.hify.identity.constant.UserStatus;
import com.hify.identity.dto.LoginResponse;
import com.hify.identity.entity.SysUser;
import com.hify.identity.mapper.SysUserMapper;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtProperties;
import com.hify.infra.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AuthService 登录逻辑单元测试：mock SysUserMapper，用真实 BCryptPasswordEncoder 与真实
 * JwtService（不连数据库）。覆盖成功 / 用户不存在 / 密码错 / 账号停用 四条路径。
 */
class AuthServiceTest {

    private SysUserMapper sysUserMapper;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        sysUserMapper = mock(SysUserMapper.class);
        passwordEncoder = new BCryptPasswordEncoder();
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-test-secret-test-secret-0123456789");
        jwtService = new JwtService(props);
        authService = new AuthService(sysUserMapper, passwordEncoder, jwtService);
    }

    private SysUser user(String username, String rawPassword, String role, UserStatus status) {
        SysUser u = new SysUser();
        u.setId(7L);
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setRole(role);
        u.setStatus(status.value());
        return u;
    }

    @Test
    void 登录成功_返回token且能解出正确身份() {
        when(sysUserMapper.selectOne(any()))
                .thenReturn(user("alice", "pw123456", CurrentUser.ROLE_MEMBER, UserStatus.ENABLED));

        LoginResponse resp = authService.login("alice", "pw123456");

        assertNotNull(resp.token());
        assertEquals(7L, resp.userId());
        assertEquals("alice", resp.username());
        assertEquals(CurrentUser.ROLE_MEMBER, resp.role());
        // token 能被同一 JwtService 解回正确身份
        CurrentUser parsed = jwtService.parseToken(resp.token());
        assertEquals(7L, parsed.userId());
        assertEquals("alice", parsed.username());
        assertEquals(CurrentUser.ROLE_MEMBER, parsed.role());
    }

    @Test
    void 用户不存在_抛11001() {
        when(sysUserMapper.selectOne(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> authService.login("ghost", "pw123456"));
        assertEquals(IdentityError.BAD_CREDENTIALS, ex.errorCode());
    }

    @Test
    void 密码错_抛11001() {
        when(sysUserMapper.selectOne(any()))
                .thenReturn(user("alice", "pw123456", CurrentUser.ROLE_MEMBER, UserStatus.ENABLED));

        BizException ex = assertThrows(BizException.class, () -> authService.login("alice", "wrong-pw"));
        assertEquals(IdentityError.BAD_CREDENTIALS, ex.errorCode());
    }

    @Test
    void 账号停用_抛11002() {
        when(sysUserMapper.selectOne(any()))
                .thenReturn(user("alice", "pw123456", CurrentUser.ROLE_MEMBER, UserStatus.DISABLED));

        // 即便密码正确，停用也先被拦下
        BizException ex = assertThrows(BizException.class, () -> authService.login("alice", "pw123456"));
        assertEquals(IdentityError.ACCOUNT_DISABLED, ex.errorCode());
    }
}
