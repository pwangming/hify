package com.hify.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.workflow.entity.WorkflowRun;
import org.apache.ibatis.annotations.Update;

/** workflow_run 访问。游标分页/僵尸重置的手写 SQL 在 Task 7/10 补充。 */
public interface WorkflowRunMapper extends BaseMapper<WorkflowRun> {

    /** 启动自愈：同步执行下，启动时仍 running 的 run 必是上次重启遗留（spec §2）。 */
    @Update("update workflow_run set status = 'failed', error_message = '服务重启中断', update_time = now() "
            + "where status = 'running' and deleted = false")
    int resetZombieRuns();
}
