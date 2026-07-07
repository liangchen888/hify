-- PostgreSQL pgvector extension 和 chunk 表初始化脚本

-- 1. 创建 pgvector 扩展（如果不存在）
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 创建 chunk 表（用于存储向量化的文本块）
CREATE TABLE IF NOT EXISTS chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    token_count INT,
    embedding vector(768),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    deleted SMALLINT DEFAULT 0,
    FOREIGN KEY (document_id) REFERENCES document(id),
    UNIQUE(document_id, chunk_index)
);

-- 3. 创建索引以加速向量查询
-- 使用 ivfflat 索引加速 L2 距离查询 (<-> 操作符)
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_ivfflat 
    ON chunk USING ivfflat (embedding vector_l2_ops) 
    WITH (lists = 100);

-- 4. 为常用查询字段创建索引
CREATE INDEX IF NOT EXISTS idx_chunk_document_id ON chunk(document_id);
CREATE INDEX IF NOT EXISTS idx_chunk_deleted ON chunk(deleted);

-- 5. 注释
COMMENT ON TABLE chunk IS '向量化文本块表 - 使用 PostgreSQL pgvector 存储 768 维向量';
COMMENT ON COLUMN chunk.embedding IS 'BGE-large-zh-v1.5 模型输出的 768 维向量';
COMMENT ON COLUMN chunk.token_count IS 'Token 计数（用于计费或限流）';
