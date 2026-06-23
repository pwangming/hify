package com.hify.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.identity.constant.IdentityError;
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

    @Transactional
    public UserView disable(Long id) {
        SysUser user = require(id);
        if (UserStatus.DISABLED.value().equals(user.getStatus())) {
            return toView(user); // 幂等：已停用直接返回，不触发护栏
        }
        assertNotLastEnabledAdmin(user);
        user.setStatus(UserStatus.DISABLED.value());
        sysUserMapper.updateById(user);
        return toView(user);
    }

    @Transactional
    public UserView enable(Long id) {
        SysUser user = require(id);
        user.setStatus(UserStatus.ENABLED.value()); // 启用不破坏不变量，无护栏；已启用再设也幂等
        sysUserMapper.updateById(user);
        return toView(user);
    }

    /** 按 id 取用户，不存在则 404。@TableLogic 保证 selectById 只命中未软删的记录。 */
    private SysUser require(Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new BizException(CommonError.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    /**
     * 统一护栏：若 target 当前是「启用的 admin」且系统里启用 admin 仅剩它一个，拒绝（11003）。
     * 停用 / 降级（admin→member）/ 删除三处在执行前都先调它，保证至少保留一个可用 admin。
     */
    private void assertNotLastEnabledAdmin(SysUser target) {
        boolean isEnabledAdmin = CurrentUser.ROLE_ADMIN.equals(target.getRole())
                && UserStatus.ENABLED.value().equals(target.getStatus());
        if (!isEnabledAdmin) {
            return;
        }
        long enabledAdmins = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, CurrentUser.ROLE_ADMIN)
                .eq(SysUser::getStatus, UserStatus.ENABLED.value()));
        if (enabledAdmins <= 1) {
            throw new BizException(IdentityError.CANNOT_REMOVE_LAST_ADMIN);
        }
    }

    /** 实体→视图投影，集中"挑哪些字段对外"的决定（passwordHash 不在内）。放在 service 层是因为
     * dto 包禁止依赖 entity 包（LayerRulesTest「协议层不碰数据访问」）。 */
    private UserView toView(SysUser u) {
        return new UserView(u.getId(), u.getUsername(), u.getRole(), u.getStatus(), u.getCreateTime());
    }
}
