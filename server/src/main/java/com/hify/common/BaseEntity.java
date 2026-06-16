package com.hify.common;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;

import java.time.OffsetDateTime;

/**
 * 所有数据库实体的公共基类（database-standards.md 第 1 节：四个字段每张表强制）。
 * 各模块 {@code entity/} 下的实体继承它，避免在每张表重复声明这四列。
 *
 * <p>字段与约定：
 * <ul>
 *   <li>{@code id} —— {@code bigint identity} 自增主键（{@link IdType#AUTO}）。
 *       注意：序列化时 Long 会被 infra 的 Jackson 全局配置转成 JSON 字符串（防 JS 精度丢失）。</li>
 *   <li>{@code deleted} —— 逻辑删除标志（{@link TableLogic}）。带它之后，所有查询会自动追加
 *       {@code where deleted = false}，delete 操作变成 {@code update set deleted = true}。
 *       字段名是 {@code deleted} 而非 {@code isDeleted}（coding-standards.md 第 2 条）。</li>
 *   <li>{@code createTime} / {@code updateTime} —— 用 {@link OffsetDateTime} 对齐 PG 的 {@code timestamptz}
 *       与 ISO-8601 带时区序列化（coding-standards.md 第 19 条）。</li>
 * </ul>
 *
 * <p>{@code @TableField(fill=...)} 只是声明「这几列要自动填充」，<b>真正的填充逻辑在 infra 的
 * MetaObjectHandler</b>（后续实现 infra 时写）：插入时填 deleted=false、create_time、update_time，
 * 更新时填 update_time。所以这一步只放注解，不会立刻生效。
 *
 * <p>无 Lombok（不在技术栈内），getter/setter 手写。
 */
public class BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Boolean deleted;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    public OffsetDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(OffsetDateTime createTime) {
        this.createTime = createTime;
    }

    public OffsetDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(OffsetDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
