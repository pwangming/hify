package com.hify.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.provider.entity.SystemSetting;
import org.apache.ibatis.annotations.Mapper;

/** system_setting 表访问（KV，按 setting_key 查单行）。 */
@Mapper
public interface SystemSettingMapper extends BaseMapper<SystemSetting> {
}
