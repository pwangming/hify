package com.hify.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.identity.constant.IdentityError;
import com.hify.identity.constant.UserStatus;
import com.hify.identity.dto.LoginResponse;
import com.hify.identity.entity.SysUser;
import com.hify.identity.mapper.SysUserMapper;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录业务逻辑。具体类 + {@code @Service}（不拆接口——code-organization.md 第 2 节）。
 *
 * <p>纯读 + 无外部 IO，不需要 {@code @Transactional}。失败一律抛 {@code BizException} + 11xxx 错误码，
 * 由 infra 全局异常处理器统一转信封。密码哈希校验用 infra 的 PasswordEncoder，令牌签发用 infra 的 JwtService。
 */
@Service
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(SysUserMapper sysUserMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(String username, String password) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        // 用户不存在与密码错返回同一错误码，不泄露账号是否存在
        if (user == null) {
            throw new BizException(IdentityError.BAD_CREDENTIALS);
        }
        if (UserStatus.DISABLED.value().equals(user.getStatus())) {
            throw new BizException(IdentityError.ACCOUNT_DISABLED);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException(IdentityError.BAD_CREDENTIALS);
        }
        CurrentUser current = new CurrentUser(user.getId(), user.getUsername(), user.getRole());
        String token = jwtService.generateToken(current);
        return new LoginResponse(token, user.getId(), user.getUsername(), user.getRole());
    }
}
