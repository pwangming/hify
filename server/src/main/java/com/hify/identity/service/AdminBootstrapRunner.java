package com.hify.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.identity.config.IdentityProperties;
import com.hify.identity.constant.UserStatus;
import com.hify.identity.entity.SysUser;
import com.hify.identity.mapper.SysUserMapper;
import com.hify.infra.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动时引导首个 admin 账号。admin 手动建其他人，但第一个 admin 由本引导器按 .env 配置创建。
 *
 * <p>幂等：仅当配置了用户名+密码、且库中无该用户名时才创建；已存在则跳过；未配置则告警跳过，
 * 不创建空密码账号。密码用 BCrypt 加密入库，日志不打印密码。
 */
@Component
@EnableConfigurationProperties(IdentityProperties.class)
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final IdentityProperties properties;
    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapRunner(IdentityProperties properties, SysUserMapper sysUserMapper,
                                PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = properties.username();
        String password = properties.password();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            log.warn("未配置 hify.identity.bootstrap-admin.username/password，跳过初始 admin 引导");
            return;
        }
        SysUser existing = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null) {
            log.info("初始 admin [{}] 已存在，跳过引导", username);
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setRole(CurrentUser.ROLE_ADMIN);
        admin.setStatus(UserStatus.ENABLED.value());
        sysUserMapper.insert(admin);
        log.info("已创建初始 admin 账号 [{}]", username);
    }
}
