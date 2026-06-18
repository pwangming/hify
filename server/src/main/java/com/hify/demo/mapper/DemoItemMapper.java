package com.hify.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.demo.entity.DemoItem;

/**
 * {@link DemoItem} 的数据访问接口。继承 {@code BaseMapper} 即获得增删改查与分页能力，
 * 无需写实现。被 {@code @MapperScan("com.hify.**.mapper")} 自动扫描注册，只允许被本模块 service 注入。
 */
public interface DemoItemMapper extends BaseMapper<DemoItem> {
}
