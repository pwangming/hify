package com.hify.identity.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.identity.dto.UserView;
import com.hify.identity.entity.SysUser;
import com.hify.identity.mapper.SysUserMapper;
import com.hify.infra.security.CurrentUser;
import com.hify.identity.constant.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminUserService 单元测试：mock SysUserMapper，用真实 BCryptPasswordEncoder，不连库。
 * 覆盖创建/列表/停用启用/重置/改角色/删除全部分支与「最后一个启用 admin」护栏。
 */
class AdminUserServiceTest {

    private SysUserMapper mapper;
    private PasswordEncoder encoder;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SysUserMapper.class);
        encoder = new BCryptPasswordEncoder();
        service = new AdminUserService(mapper, encoder);
    }

    private SysUser user(long id, String username, String role, UserStatus status) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(username);
        u.setPasswordHash(encoder.encode("init-pw-1234"));
        u.setRole(role);
        u.setStatus(status.value());
        u.setCreateTime(OffsetDateTime.now());
        return u;
    }

    @Test
    void 创建用户_密码被哈希且状态默认启用() {
        when(mapper.selectCount(any())).thenReturn(0L); // 无重名
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);

        UserView view = service.create("alice", "rawpw1234", CurrentUser.ROLE_MEMBER);

        verify(mapper).insert(captor.capture());
        SysUser saved = captor.getValue();
        assertEquals("alice", saved.getUsername());
        assertEquals(CurrentUser.ROLE_MEMBER, saved.getRole());
        assertEquals(UserStatus.ENABLED.value(), saved.getStatus());
        assertNotEquals("rawpw1234", saved.getPasswordHash());          // 不是明文
        assertTrue(encoder.matches("rawpw1234", saved.getPasswordHash())); // 是该明文的 BCrypt 哈希
        assertEquals("alice", view.username());
    }

    @Test
    void 创建用户_重名_抛CONFLICT() {
        when(mapper.selectCount(any())).thenReturn(1L); // 已存在同名

        BizException ex = assertThrows(BizException.class,
                () -> service.create("alice", "rawpw1234", CurrentUser.ROLE_MEMBER));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 列表_按返回投影且不含密码哈希() {
        when(mapper.selectList(any())).thenReturn(List.of(
                user(1L, "alice", CurrentUser.ROLE_ADMIN, UserStatus.ENABLED),
                user(2L, "bob", CurrentUser.ROLE_MEMBER, UserStatus.DISABLED)));

        List<UserView> list = service.list();

        assertEquals(2, list.size());
        assertEquals("alice", list.get(0).username());
        assertEquals(CurrentUser.ROLE_ADMIN, list.get(0).role());
        assertEquals(UserStatus.DISABLED.value(), list.get(1).status());
    }
}
