package com.hify.workflow.dto;

import java.time.OffsetDateTime;

/** 草稿视图。updateTime 供前端显示「上次保存时间」。 */
public record DraftResponse(GraphDef graph, OffsetDateTime updateTime) {
}
