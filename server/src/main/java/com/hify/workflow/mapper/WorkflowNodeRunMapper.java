package com.hify.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.workflow.entity.WorkflowNodeRun;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** workflow_node_run 访问（分区日志表）。分区补建/僵尸重置的手写 SQL 在 Task 7 补充。 */
public interface WorkflowNodeRunMapper extends BaseMapper<WorkflowNodeRun> {

    /**
     * 幂等补建月分区（DDL）。name/from/to 由 WorkflowPartitionMaintainer 从日期计算、非用户输入，
     * ${} 拼接安全（标识符/DDL 无法用 #{} 占位）。照抄 usage LlmCallLogMapper 先例。
     */
    @Update("create table if not exists ${name} partition of workflow_node_run "
            + "for values from ('${from}') to ('${to}')")
    void createMonthlyPartition(@Param("name") String name, @Param("from") String from, @Param("to") String to);

    /** 启动自愈：running 的节点日志随 run 一并置 failed。 */
    @Update("update workflow_node_run set status = 'failed', error_message = '服务重启中断', update_time = now() "
            + "where status = 'running'")
    int resetZombieNodeRuns();
}
