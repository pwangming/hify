package com.hify.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 文档表 {@code kb_document} 映射实体。继承 BaseEntity（id/createTime/updateTime/deleted）。
 * content 是原始文件 bytea；列表查询必须显式排除本列（database-standards：大列不进列表）。
 */
@TableName("kb_document")
public class KbDocument extends BaseEntity {

    private Long datasetId;
    private String name;
    private String fileType;
    private Long fileSize;
    private byte[] content;
    private String status;
    private Integer chunkCount;
    private Integer chunkSize;
    private Integer chunkOverlap;

    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }

    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }

    public Integer getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(Integer chunkOverlap) { this.chunkOverlap = chunkOverlap; }
}
