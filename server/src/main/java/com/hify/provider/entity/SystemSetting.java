package com.hify.provider.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 系统设置 KV 表 {@code system_setting} 映射实体。当前归 provider 模块管理（K3 spec 决策 2）；
 * K3 仅一个键 embedding_model_id，值存字符串（Long 的十进制形态）。
 */
@TableName("system_setting")
public class SystemSetting extends BaseEntity {

    private String settingKey;
    private String settingValue;

    public String getSettingKey() { return settingKey; }
    public void setSettingKey(String settingKey) { this.settingKey = settingKey; }

    public String getSettingValue() { return settingValue; }
    public void setSettingValue(String settingValue) { this.settingValue = settingValue; }
}
