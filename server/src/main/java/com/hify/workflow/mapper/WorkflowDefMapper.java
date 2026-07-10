package com.hify.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.entity.WorkflowDef;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** workflow_def 访问。graph 是大列：列表场景禁 select *（W1 只有按 app 单条读，不受影响）。 */
public interface WorkflowDefMapper extends BaseMapper<WorkflowDef> {

    /**
     * 草稿插或改一律 UPSERT（db-standards §6.3 禁先查后插）。冲突目标是部分唯一索引
     * workflow_def_app_version_uq，故 on conflict 需带 where 子句匹配索引谓词。
     */
    @Insert("insert into workflow_def (app_id, version, graph) values (#{appId}, 1, "
            + "#{graph,typeHandler=com.hify.workflow.config.GraphDefTypeHandler}) "
            + "on conflict (app_id, version) where deleted = false "
            + "do update set graph = excluded.graph, update_time = now()")
    void upsertDraft(@Param("appId") Long appId, @Param("graph") GraphDef graph);
}
