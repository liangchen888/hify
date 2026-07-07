package com.hify.knowledge.dto;

/**
 * 知识库检索结果，包含匹配的文本块及来源信息。
 * 用于手动检索接口和 RAG 来源追溯。
 */
public class ChunkSearchResult {

    /** chunk 主键 */
    private Long id;

    /** 所属文档 id */
    private Long documentId;

    /** 文档名称（便于前端展示来源） */
    private String documentName;

    /** chunk 在文档中的序号 */
    private Integer chunkIndex;

    /** 匹配的文本内容 */
    private String content;

    /** token 数量 */
    private Integer tokenCount;

    /**
     * 与查询的相似度分数（0~1，越高越相关）。
     * 由 L2 距离转换而来：score = 1 / (1 + distance)
     */
    private Double score;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }

    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }

    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
}
