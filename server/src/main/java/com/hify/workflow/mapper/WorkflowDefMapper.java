package com.hify.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.workflow.entity.WorkflowDef;

/** workflow_def 访问。graph 是大列：列表场景禁 select *（W1 只有按 app 单条读，不受影响）。 */
public interface WorkflowDefMapper extends BaseMapper<WorkflowDef> {
}
