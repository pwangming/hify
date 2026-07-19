package com.hify.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.knowledge.dto.ChunkHit;
import com.hify.knowledge.entity.KbChunk;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** kb_chunk 表访问。批量写走多值 insert（database-standards §2.1）。 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 多值批量插入（一条 SQL 插 N 行），跑在调用方的 Spring 事务里。
     * 每批 ≤ 1000 行由调用方保证（4 参数/行 × 1000 远低于 PG 协议 65535 参数上限）。
     * deleted/create_time/update_time 依赖 DB 默认值。
     */
    @Insert("""
            <script>
            insert into kb_chunk (document_id, dataset_id, position, content)
            values
            <foreach collection="chunks" item="c" separator=",">
            (#{c.documentId}, #{c.datasetId}, #{c.position}, #{c.content})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("chunks") List<KbChunk> chunks);

    @Select("select id, content from kb_chunk where document_id = #{documentId} "
            + "and deleted = false and embedding is null order by position")
    List<KbChunk> selectUnembedded(@Param("documentId") Long documentId);

    @Update("update kb_chunk set embedding = #{vector}::vector, update_time = now() "
            + "where id = #{id} and deleted = false")
    int updateEmbedding(@Param("id") Long id, @Param("vector") String vector);

    /**
     * 清空单个文档的向量，供全量重嵌逐份重做。
     *
     * <p>不提供「全表清空」的版本：重嵌必须先 claim 到文档再清它自己的向量，否则会连带清掉
     * 正在 pending/processing 的文档——那些文档不在重嵌名单里，最后会以「状态 ready、向量 NULL」
     * 的姿态在检索中静默消失。
     */
    @Update("update kb_chunk set embedding = null, update_time = now() "
            + "where document_id = #{documentId} and deleted = false")
    int clearEmbeddingsByDocument(@Param("documentId") Long documentId);

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
