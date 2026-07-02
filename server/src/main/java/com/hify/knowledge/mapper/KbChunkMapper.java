package com.hify.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.knowledge.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;

/** kb_chunk 表访问。批量写走 Db.saveBatch（database-standards §2.1）。 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {
}
