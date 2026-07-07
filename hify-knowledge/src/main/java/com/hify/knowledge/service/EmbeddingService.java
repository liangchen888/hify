package com.hify.knowledge.service;

import com.pgvector.PGvector;

/**
 * 嵌入向量化服务 - 调用BGE模型生成向量
 */
public interface EmbeddingService {
    
    /**
     * 将文本转换为向量
     * @param text 输入文本
     * @return 向量（768维）
     */
    PGvector embed(String text);
    
    /**
     * 批量转换文本为向量
     * @param texts 文本列表
     * @return 向量列表
     */
    java.util.List<PGvector> embedBatch(java.util.List<String> texts);
}
