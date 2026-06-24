package com.hify.app.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.app.api.dto.AppConfig;
import com.hify.app.config.AppConfigTypeHandler;
import com.hify.common.BaseEntity;

/**
 * 应用表 {@code app} 映射实体。继承 BaseEntity（id/createTime/updateTime/deleted）。
 * config 是 jsonb，经 {@link AppConfigTypeHandler} 读写；autoResultMap=true 才让该处理器在查询映射时生效。
 * type/status 存小写字符串（见 AppType/AppStatus 与 DB check）。
 */
@TableName(value = "app", autoResultMap = true)
public class App extends BaseEntity {

    private String name;
    private String description;
    private String type;
    private Long modelId;

    @TableField(typeHandler = AppConfigTypeHandler.class)
    private AppConfig config;

    private Long ownerId;
    private String status;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }

    public AppConfig getConfig() { return config; }
    public void setConfig(AppConfig config) { this.config = config; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
