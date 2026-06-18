package com.hify.demo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 演示表 {@code demo_item} 的映射实体。继承 {@link BaseEntity}，自动带
 * id / create_time / update_time / deleted 四列（填充逻辑在 infra 的 MetaObjectHandler）。
 */
@TableName("demo_item")
public class DemoItem extends BaseEntity {

    private String name;
    private Integer status;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
