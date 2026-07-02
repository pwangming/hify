package com.hify.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 分段表 {@code kb_chunk} 映射实体。dataset_id 为检索用冗余列；embedding 列 K3 迁移补加。
 */
@TableName("kb_chunk")
public class KbChunk extends BaseEntity {

    private Long documentId;
    private Long datasetId;
    private Integer position;
    private String content;

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }

    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }

    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
