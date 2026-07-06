package com.hify.knowledge.dto;

/**
 * 向量检索命中投影（KbChunkMapper.searchByVector 结果映射，模块内用）。
 * 不复用 KbChunk 实体：多出 documentName/score 两个跨表计算列。score = 1 - 余弦距离，越大越相关。
 * POJO 而非 record：MyBatis 结果映射走 setter（map-underscore-to-camel-case）。
 */
public class ChunkHit {

    private Long id;
    private Long documentId;
    private String documentName;
    private String content;
    private Double score;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }

    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
}
