package com.hify.identity.service;

import com.hify.identity.config.IdentityProperties;
import com.hify.identity.constant.UserStatus;
import com.hify.identity.entity.SysUser;
import com.hify.identity.mapper.SysUserMapper;
import com.hify.infra.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminBootstrapRunner 单元测试：mock SysUserMapper，真实 BCryptPasswordEncoder（不连库）。
 * 覆盖：已配置且无该用户→建 admin；已存在→不建；未配置→不建。
 */
class AdminBootstrapRunnerTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    void 已配置且库中无该用户_创建admin() throws Exception {
        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        IdentityProperties props = new IdentityProperties("root", "secret-pw");
        AdminBootstrapRunner runner = new AdminBootstrapRunner(props, mapper, passwordEncoder);

        runner.run(null);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(mapper).insert(captor.capture());
        SysUser created = captor.getValue();
        assertEquals("root", created.getUsername());
        assertEquals(CurrentUser.ROLE_ADMIN, created.getRole());
        assertEquals(UserStatus.ENABLED.value(), created.getStatus());
        assertTrue(passwordEncoder.matches("secret-pw", created.getPasswordHash()));
    }

    @Test
    void 该用户已存在_不重复创建() throws Exception {
        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.selectOne(any())).thenReturn(new SysUser());
        IdentityProperties props = new IdentityProperties("root", "secret-pw");
        AdminBootstrapRunner runner = new AdminBootstrapRunner(props, mapper, passwordEncoder);

        runner.run(null);

        verify(mapper, never()).insert(any(SysUser.class));
    }

    @Test
    void 未配置用户名或密码_不创建() throws Exception {
        SysUserMapper mapper = mock(SysUserMapper.class);
        IdentityProperties props = new IdentityProperties("", "");
        AdminBootstrapRunner runner = new AdminBootstrapRunner(props, mapper, passwordEncoder);

        runner.run(null);

        verify(mapper, never()).insert(any(SysUser.class));
    }
}
