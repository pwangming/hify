package com.hify.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.knowledge.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** kb_document 表访问。注解 SQL 不享受 @TableLogic 自动过滤，必须手写 deleted = false。 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {

    @Update("update kb_document set status = 'processing', error_message = null, update_time = now() "
            + "where id = #{id} and status = #{from} and deleted = false")
    int claimStatus(@Param("id") Long id, @Param("from") String from);

    @Update("update kb_document set status = 'processing', error_message = null, update_time = now() "
            + "where id = #{id} and status in ('ready', 'failed') and deleted = false")
    int claimForReembed(@Param("id") Long id);

    @Update("update kb_document set status = 'ready', error_message = null, update_time = now() "
            + "where id = #{id} and deleted = false")
    int markReady(@Param("id") Long id);

    @Update("update kb_document set status = 'failed', error_message = #{msg}, update_time = now() "
            + "where id = #{id} and deleted = false")
    int markFailed(@Param("id") Long id, @Param("msg") String msg);

    @Update("update kb_document set status = 'failed', error_message = '服务重启，处理中断，请重试', "
            + "update_time = now() where status in ('pending', 'processing') and deleted = false")
    int failZombies();

    @Select("select id from kb_document where status in ('ready', 'failed') and deleted = false order by id")
    List<Long> selectReembedTargetIds();
}
