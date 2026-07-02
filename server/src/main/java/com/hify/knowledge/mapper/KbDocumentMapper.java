package com.hify.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.knowledge.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;

/** kb_document 表访问。K2 纯框架 CRUD，无手写 SQL。 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {
}
