package com.hify.tool.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;
import com.hify.tool.config.OpenApiToolSpecTypeHandler;
import com.hify.tool.service.openapi.OpenApiToolSpec;

/**
 * 工具注册表 {@code tool} 映射实体。source 分 builtin/openapi/mcp；
 * name 为模型寻址标识与内置执行器绑定键。openapi 行 spec(jsonb) 存执行描述，builtin 恒空。
 */
@TableName(value = "tool", autoResultMap = true)
public class Tool extends BaseEntity {

    private String name;
    private String description;
    private String source;
    private Boolean enabled;
    private Long ownerId;
    @TableField(typeHandler = OpenApiToolSpecTypeHandler.class)
    private OpenApiToolSpec spec;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public OpenApiToolSpec getSpec() { return spec; }
    public void setSpec(OpenApiToolSpec spec) { this.spec = spec; }
}
