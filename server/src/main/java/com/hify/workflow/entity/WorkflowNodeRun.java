package com.hify.workflow.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.workflow.config.JsonbMapTypeHandler;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * workflow_node_run 映射。分区日志表：无 deleted（清理=drop 分区），故不继承 BaseEntity。
 * DB 主键是 (id, create_time)（分区键必须入 pk）；MP 仍按 id 定位（updateById 跨分区扫 id 索引，W1 量级无碍）。
 */
@TableName(value = "workflow_node_run", autoResultMap = true)
public class WorkflowNodeRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private String nodeId;
    private String nodeType;
    private String status;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> inputs;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> outputs;

    private String errorMessage;
    private Long elapsedMs;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> getInputs() { return inputs; }
    public void setInputs(Map<String, Object> inputs) { this.inputs = inputs; }

    public Map<String, Object> getOutputs() { return outputs; }
    public void setOutputs(Map<String, Object> outputs) { this.outputs = outputs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(Long elapsedMs) { this.elapsedMs = elapsedMs; }

    public OffsetDateTime getCreateTime() { return createTime; }
    public void setCreateTime(OffsetDateTime createTime) { this.createTime = createTime; }

    public OffsetDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(OffsetDateTime updateTime) { this.updateTime = updateTime; }
}
