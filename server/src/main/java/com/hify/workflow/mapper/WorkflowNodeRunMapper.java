package com.hify.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.workflow.entity.WorkflowNodeRun;

/** workflow_node_run 访问（分区日志表）。分区补建/僵尸重置的手写 SQL 在 Task 7 补充。 */
public interface WorkflowNodeRunMapper extends BaseMapper<WorkflowNodeRun> {
}
