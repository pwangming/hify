package com.hify.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;
import com.hify.workflow.config.JsonbMapTypeHandler;

import java.util.Map;

/** workflow_run 映射。status 存小写字符串（RunStatus）。inputs/outputs jsonb 经 JsonbMapTypeHandler。 */
@TableName(value = "workflow_run", autoResultMap = true)
public class WorkflowRun extends BaseEntity {

    private Long appId;
    private Long defId;
    private Long userId;
    private String status;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> inputs;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> outputs;

    private String errorMessage;
    private Long elapsedMs;

    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }

    public Long getDefId() { return defId; }
    public void setDefId(Long defId) { this.defId = defId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

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
}
