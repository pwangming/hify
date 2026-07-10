package com.hify.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.workflow.entity.WorkflowRun;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/** workflow_run 访问。游标分页/僵尸重置的手写 SQL 在 Task 7/10 补充。 */
public interface WorkflowRunMapper extends BaseMapper<WorkflowRun> {

    /** 启动自愈：同步执行下，启动时仍 running 的 run 必是上次重启遗留（spec §2）。 */
    @Update("update workflow_run set status = 'failed', error_message = '服务重启中断', update_time = now() "
            + "where status = 'running' and deleted = false")
    int resetZombieRuns();

    /** 运行历史首页（摘要列，禁大列 inputs/outputs——db-standards §4 通用禁令）。多查一条探测 hasMore。 */
    @Select("select id, app_id, def_id, user_id, status, error_message, elapsed_ms, create_time, update_time "
            + "from workflow_run where app_id = #{appId} and deleted = false "
            + "order by create_time desc, id desc limit #{limit}")
    List<WorkflowRun> firstPage(@Param("appId") Long appId, @Param("limit") int limit);

    /** 游标翻页：keyset (create_time,id) 严格递减（db-standards §4 消息流模板同款）。 */
    @Select("select id, app_id, def_id, user_id, status, error_message, elapsed_ms, create_time, update_time "
            + "from workflow_run where app_id = #{appId} and deleted = false "
            + "and (create_time, id) < (#{createTime}, #{id}) "
            + "order by create_time desc, id desc limit #{limit}")
    List<WorkflowRun> afterCursor(@Param("appId") Long appId, @Param("createTime") OffsetDateTime createTime,
                                  @Param("id") Long id, @Param("limit") int limit);
}
