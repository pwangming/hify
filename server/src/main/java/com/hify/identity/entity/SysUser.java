package com.hify.identity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 系统用户表 {@code sys_user} 的映射实体。继承 {@link BaseEntity}，自动带
 * id / create_time / update_time / deleted 四列（填充逻辑在 infra 的 MetaObjectHandler）。
 *
 * <p>{@code role} 取值 admin/member（与 infra CurrentUser.ROLE_* 一致），
 * {@code status} 取值 enabled/disabled（见 {@code UserStatus}）。
 * MyBatis-Plus 默认开启驼峰↔下划线映射：passwordHash ↔ password_hash。
 */
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private String username;
    private String passwordHash;
    private String role;
    private String status;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
