package com.hify.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.identity.constant.UserStatus;
import com.hify.identity.dto.UserView;
import com.hify.identity.entity.SysUser;
import com.hify.identity.mapper.SysUserMapper;
import com.hify.infra.security.CurrentUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * admin 用户管理业务逻辑（具体类 + @Service，不拆接口——code-organization.md 第 2 节）。
 * 注入 SysUserMapper 与 infra 的 PasswordEncoder；失败一律抛 BizException 交全局处理器转信封。
 */
@Service
public class AdminUserService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;

    public AdminUserService(SysUserMapper sysUserMapper, PasswordEncoder passwordEncoder) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserView create(String username, String rawPassword, String role) {
        long sameName = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (sameName > 0) {
            throw new BizException(CommonError.CONFLICT, "用户名已存在");
        }
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setRole(role);
        u.setStatus(UserStatus.ENABLED.value());
        sysUserMapper.insert(u);
        return toView(u);
    }

    public List<UserView> list() {
        List<SysUser> users = sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>().orderByDesc(SysUser::getCreateTime));
        return users.stream().map(this::toView).toList();
    }

    /** 实体→视图投影，集中"挑哪些字段对外"的决定（passwordHash 不在内）。放在 service 层是因为
     * dto 包禁止依赖 entity 包（LayerRulesTest「协议层不碰数据访问」）。 */
    private UserView toView(SysUser u) {
        return new UserView(u.getId(), u.getUsername(), u.getRole(), u.getStatus(), u.getCreateTime());
    }
}
