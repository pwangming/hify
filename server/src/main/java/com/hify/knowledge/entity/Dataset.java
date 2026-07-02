package com.hify.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 知识库表 {@code dataset} 映射实体。继承 BaseEntity（id/createTime/updateTime/deleted，软删由基类 @TableLogic 生效）。
 */
@TableName("dataset")
public class Dataset extends BaseEntity {

    private String name;
    private String description;
    private Long ownerId;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
}
