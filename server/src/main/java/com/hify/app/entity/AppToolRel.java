package com.hify.app.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/** 应用↔工具关系表 {@code app_tool_rel} 映射实体。tool_id 跨模块弱引用；更新=全量替换（软删+插新）。 */
@TableName("app_tool_rel")
public class AppToolRel extends BaseEntity {

    private Long appId;
    private Long toolId;

    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }

    public Long getToolId() { return toolId; }
    public void setToolId(Long toolId) { this.toolId = toolId; }
}
