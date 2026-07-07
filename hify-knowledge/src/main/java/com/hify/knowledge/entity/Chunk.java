package com.hify.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;
import com.pgvector.PGvector;



/**
 * 向量化文本块实体 - 使用 PostgreSQL pgvector 存储向量
 */
@TableName("chunk")
public class Chunk extends BaseEntity {
    private Long documentId;      // 所属文档 ID
    private Integer chunkIndex;   // 块序号
    private String content;       // 原始文本内容
    private Integer tokenCount;   // Token 计数
    
    // 向量化字段（pgvector 类型）
    // 对应 PostgreSQL: embedding vector(768) -- BGE 模型输出维度
    private PGvector embedding;   // 768 维向量

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public PGvector getEmbedding() {
        return embedding;
    }

    public void setEmbedding(PGvector embedding) {
        this.embedding = embedding;
    }
}
