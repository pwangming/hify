package com.hify.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;
import com.hify.workflow.config.GraphDefTypeHandler;
import com.hify.workflow.dto.GraphDef;

/** workflow_def 映射。graph 经 GraphDefTypeHandler 读写（autoResultMap=true 才在查询映射时生效）。 */
@TableName(value = "workflow_def", autoResultMap = true)
public class WorkflowDef extends BaseEntity {

    private Long appId;
    private Integer version;

    @TableField(typeHandler = GraphDefTypeHandler.class)
    private GraphDef graph;

    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public GraphDef getGraph() { return graph; }
    public void setGraph(GraphDef graph) { this.graph = graph; }
}
