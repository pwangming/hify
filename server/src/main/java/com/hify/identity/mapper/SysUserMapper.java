package com.hify.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.identity.entity.SysUser;

/**
 * {@link SysUser} 的数据访问接口。继承 {@code BaseMapper} 即获得增删改查能力，
 * 被 {@code @MapperScan("com.hify.**.mapper")} 自动扫描注册，只允许被本模块 service 注入。
 * 按 username 查询用 service 层的 LambdaQueryWrapper，无需在此加自定义方法。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {
}
