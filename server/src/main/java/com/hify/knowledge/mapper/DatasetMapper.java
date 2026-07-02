package com.hify.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.knowledge.entity.Dataset;
import org.apache.ibatis.annotations.Mapper;

/** dataset 表访问。K1 纯框架 CRUD，无手写 SQL。 */
@Mapper
public interface DatasetMapper extends BaseMapper<Dataset> {
}
