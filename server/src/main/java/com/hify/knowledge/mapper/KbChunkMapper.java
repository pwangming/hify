package com.hify.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.knowledge.dto.ChunkHit;
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

    /**
     * 向量检索（database-standards §2.1 先过滤后排序模板；K4 拍板手写 SQL，不走 VectorStore）。
     * {@code <=>} 为余弦距离，score = 1 - 距离；多库过滤用 foreach in（与 any(?) 等价，MyBatis 免数组 TypeHandler）。
     * 阈值过滤在 RetrievalService（Java 层），SQL 只管 topK——阈值进 where 会干扰 HNSW 索引走法。
     */
    @Select("""
            <script>
            select c.id, c.document_id, d.name as document_name, c.content,
                   1 - (c.embedding <![CDATA[<=>]]> #{qvec}::vector) as score
            from kb_chunk c
            join kb_document d on d.id = c.document_id
            where c.dataset_id in
              <foreach collection="datasetIds" item="dsId" open="(" separator="," close=")">#{dsId}</foreach>
              and c.deleted = false and d.deleted = false
              and c.embedding is not null
            order by c.embedding <![CDATA[<=>]]> #{qvec}::vector
            limit #{topK}
            </script>
            """)
    List<ChunkHit> searchByVector(@Param("datasetIds") List<Long> datasetIds,
                                  @Param("qvec") String qvec,
                                  @Param("topK") int topK);
}
