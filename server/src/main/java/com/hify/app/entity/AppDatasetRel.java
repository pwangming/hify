package com.hify.app.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/** 应用↔知识库关系表 {@code app_dataset_rel} 映射实体。dataset_id 跨模块弱引用；更新=全量替换（软删+插新）。 */
@TableName("app_dataset_rel")
public class AppDatasetRel extends BaseEntity {

    private Long appId;
    private Long datasetId;

    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }

    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
}
