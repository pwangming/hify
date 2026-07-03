package com.hify.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.knowledge.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** kb_chunk 表访问。批量写走 Db.saveBatch（database-standards §2.1）。 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    @Select("select id, content from kb_chunk where document_id = #{documentId} "
            + "and deleted = false and embedding is null order by position")
    List<KbChunk> selectUnembedded(@Param("documentId") Long documentId);

    @Update("update kb_chunk set embedding = #{vector}::vector, update_time = now() "
            + "where id = #{id} and deleted = false")
    int updateEmbedding(@Param("id") Long id, @Param("vector") String vector);

    @Update("update kb_chunk set embedding = null, update_time = now() where deleted = false")
    int clearAllEmbeddings();
}
