package com.hify.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.provider.entity.AiModel;

/**
 * {@link AiModel} 的数据访问接口。继承 {@code BaseMapper} 即获得增删改查能力。
 * 由 {@code @MapperScan("com.hify.**.mapper")} 自动扫描注册，只允许被本模块 service 注入。
 */
public interface AiModelMapper extends BaseMapper<AiModel> {
}
