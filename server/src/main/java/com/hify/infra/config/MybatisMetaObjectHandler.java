package com.hify.infra.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 公共字段自动填充器（database-standards.md 第 1.1 节）。
 *
 * <p>{@code common.BaseEntity} 上用 {@code @TableField(fill=...)} 声明了「这些列要自动填」，
 * 但<b>真正的填充逻辑在这里</b>。MyBatis-Plus 在 insert/update 前会回调本类，由它给
 * create_time / update_time / deleted 赋值，业务代码因此<b>永远不用手动 set 这三列</b>。
 *
 * <ul>
 *   <li>插入：create_time = update_time = 当前时间；deleted = false；</li>
 *   <li>更新：仅刷新 update_time。</li>
 * </ul>
 *
 * <p>用 {@code strictInsertFill/strictUpdateFill}：它们会核对字段名与 {@code @TableField} 的 fill
 * 策略，只填声明过的列，不会乱填。时间用 {@link OffsetDateTime} 与 {@code timestamptz} 对齐。
 */
@Component
public class MybatisMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now();
        strictInsertFill(metaObject, "createTime", OffsetDateTime.class, now);
        strictInsertFill(metaObject, "updateTime", OffsetDateTime.class, now);
        strictInsertFill(metaObject, "deleted", Boolean.class, false);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateTime", OffsetDateTime.class, OffsetDateTime.now());
    }
}
